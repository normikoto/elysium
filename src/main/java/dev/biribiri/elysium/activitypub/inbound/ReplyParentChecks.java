package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.http.HostBusyException;
import dev.biribiri.elysium.activitypub.jobs.ActivityJobs;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Keeps a public or unlisted reply that's addressed to nobody here, and whose parent isn't stored, if the parent turns out
 * to be by an account followed here. That's how a reply that the parent's server forwards to its author's followers looks
 * when it arrives before the parent does, since jobs have no order, or when the parent was posted before anyone here
 * followed its author. {@link NoteStorage} would otherwise ignore it for good.
 *
 * <p>Anyone can send a reply naming any URL as its parent, so a reply only waits if someone here follows an actor on the
 * parent's server. Otherwise any server could have this one fetch whatever it liked, and the parent couldn't be by anyone
 * followed here anyway.
 *
 * <p>Each reply waits {@link ThreadFetches#PARENT_WAIT} first, since the parent's own {@code Create} is usually only a
 * little behind, and then needs no request. Otherwise one {@link ParentFetchJob} fetches the parent for every reply waiting
 * for it, signed as the instance actor, as a thread's parents are. Each reply fetching it would use up its server's turns in
 * {@link TargetFetchTurns}, and the replies refused one would be lost. The parent is only stored if its author is already
 * stored here and has a local follower, since storing it is what a stranger's post mustn't get, which is what
 * {@link NoteStorage} keeps out. Each reply checks again every {@link ThreadFetches#PARENT_WAIT}, until the parent is stored
 * or its fetch is over.
 *
 * <p>Once the parent is stored, each reply's activity is handled again, so it goes through every check it would have if the
 * parent had come first, such as a follower's block of its author. Those find the parent stored, so they don't queue this
 * again.
 */
@Component
class ReplyParentChecks {

    static final TaskDescriptor<ReplyParentJob> TASK = TaskDescriptor.of("reply-parent", ReplyParentJob.class);

    static final TaskDescriptor<ParentFetchJob> FETCH_TASK = TaskDescriptor.of("reply-parent-fetch", ParentFetchJob.class);

    private static final Logger log = LoggerFactory.getLogger(ReplyParentChecks.class);

    private final SchedulerClient scheduler;
    private final StoredActivities activities;
    private final ObjectProvider<InboundActivityHandler> handlers;
    private final ActivityDocuments documents;
    private final TargetFetchTurns turns;
    private final ThreadFetches threads;
    private final RemoteStatuses statuses;
    private final NoteStorage storage;
    private final FollowService follows;
    private final ActorService actors;
    private final DomainBlocks blocks;

    /**
     * @param scheduler lazy, because the scheduler needs the inbound task, which needs the handlers, which need this
     * @param handlers  looked up when a job runs, since the handlers queue these jobs
     */
    ReplyParentChecks(
            @Lazy SchedulerClient scheduler,
            StoredActivities activities,
            ObjectProvider<InboundActivityHandler> handlers,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            ThreadFetches threads,
            RemoteStatuses statuses,
            NoteStorage storage,
            FollowService follows,
            ActorService actors,
            DomainBlocks blocks) {
        this.scheduler = scheduler;
        this.activities = activities;
        this.handlers = handlers;
        this.documents = documents;
        this.turns = turns;
        this.threads = threads;
        this.statuses = statuses;
        this.storage = storage;
        this.follows = follows;
        this.actors = actors;
        this.blocks = blocks;
    }

    /**
     * Queues a check, unless one for the same activity is already queued, or the parent couldn't be by anyone followed here.
     */
    void queue(InboundActivity activity, URI parent) {
        // One on this server or a blocked one could never be fetched.
        if (!threads.isFetchable(parent) || !follows.hasLocalFollowersOn(RemoteActorDocument.domainOf(parent))) {
            log.debug("Ignoring {}, whose parent {} can't be by anyone followed here", activity.id(), parent);
            return;
        }
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Jobs.scheduleAt(scheduler, TASK, new ReplyParentJob(activity.id(), parent.toString(), now, false),
                now.plus(ThreadFetches.PARENT_WAIT), "reply-parent", activity.id());
    }

    @Bean
    static Task<ReplyParentJob> replyParent(ObjectProvider<ReplyParentChecks> replyParents) {
        return Jobs.task(TASK, job -> replyParents.getObject().run(job));
    }

    @Bean
    static Task<ParentFetchJob> replyParentFetch(ObjectProvider<ReplyParentChecks> replyParents) {
        return Jobs.task(FETCH_TASK, job -> replyParents.getObject().fetch(job));
    }

    /**
     * @return what to do with the job, which is to check again later while the parent is being fetched
     */
    CompletionHandler<ReplyParentJob> run(ReplyParentJob job) {
        try {
            return check(job);
        } catch (PermanentJobFailure e) {
            // Such as an author who's been suspended here, which isn't worth parking the job for.
            log.debug("Not keeping the reply of {}: {}", job.activityId(), e.getMessage());
            return Jobs.done();
        }
    }

    private CompletionHandler<ReplyParentJob> check(ReplyParentJob job) {
        var activity = activities.find(job.activityId());
        if (activity.isEmpty()) {
            return ActivityJobs.activityGone(job);
        }
        var sender = URI.create(activity.get().actorUri());
        // A block or suspension can have landed while this waited.
        if (blocks.isBlocked(sender.getHost()) || actors.isInactive(sender)) {
            return Jobs.done();
        }
        var parent = URI.create(job.parentUri());
        // Before looking for the parent, since a fetch that stores it and finishes in between would otherwise look like one
        // that found nothing.
        var fetching = job.fetchQueued() && Jobs.isQueued(scheduler, FETCH_TASK, job.parentUri());
        if (statuses.isStored(parent)) {
            handleAgain(activity.get());
            return Jobs.done();
        }
        var now = Instant.now();
        if (!job.fetchQueued()) {
            Jobs.schedule(scheduler, FETCH_TASK,
                    new ParentFetchJob(job.parentUri(), now.truncatedTo(ChronoUnit.MICROS), null), job.parentUri());
            return Jobs.rescheduleAt(now.plus(ThreadFetches.PARENT_WAIT), job.afterQueuingFetch());
        }
        // A fetch that's waited this long for its server has stopped being retried.
        if (fetching && !job.isOverdue(now)) {
            return Jobs.rescheduleAt(now.plus(ThreadFetches.PARENT_WAIT), job);
        }
        log.debug("Ignoring {}, whose parent {} wasn't found", activity.get().id(), parent);
        return Jobs.done();
    }

    /**
     * @return what to do with the job, which is to wait if its turn to fetch the parent hasn't come yet
     * @throws IllegalStateException if the parent's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<ParentFetchJob> fetch(ParentFetchJob job) {
        var parent = URI.create(job.parentUri());
        if (statuses.isStored(parent) || !threads.isFetchable(parent)) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(parent, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Not fetching {} for replies to it, since too many fetches are waiting for its server", parent);
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.waitUntil(turn.get(), job.withTurn(turn.get()));
        }
        Optional<AttributedNote> note;
        try {
            note = AttributedNote.fetch(parent, documents);
        } catch (HostBusyException e) {
            return Jobs.waitForHost(job, job::withTurn);
        }
        if (note.isEmpty()) {
            return Jobs.done();
        }
        // The replies' own handling checks blocks, since they can have different authors.
        if (!follows.hasLocalFollowers(note.get().author())) {
            log.debug("Not storing {}, which isn't by anyone followed here", parent);
            return Jobs.done();
        }
        try {
            if (storage.storeInThread(note.get().note(), note.get().author()) != NoteStorage.Result.IGNORED) {
                // As its own Create would have, since the replies' handling finds it stored and fetches nothing above it.
                threads.fetchParentsOf(note.get().note(), note.get().author());
            }
        } catch (PermanentJobFailure e) {
            log.debug("Not storing {}: {}", parent, e.getMessage());
        }
        return Jobs.done();
    }

    private void handleAgain(InboundActivity activity) {
        handlers.stream()
                .filter(handler -> handler.type().equals(activity.type()))
                .findFirst()
                .ifPresent(handler -> handler.handle(activity));
    }
}
