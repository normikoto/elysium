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
 * to be by a local account or one followed here. That's how a reply that the parent's server forwards to its author's
 * followers looks when it arrives before the parent does, since jobs have no order, or when the parent was posted before
 * anyone here followed its author. {@link NoteStorage} would otherwise ignore it for good.
 *
 * <p>It waits {@link ThreadFetches#PARENT_WAIT} first, since the parent's own {@code Create} is usually only a little
 * behind, and then needs no request. Otherwise the parent is fetched, signed as the instance actor, as a thread's parents
 * are. Anyone can send a reply naming any status, so the parent is only stored if its author is already stored here and
 * {@link LocalRecipients#concernsSomeoneHere} passes for them. Otherwise any server could have strangers' posts stored,
 * which is what {@link NoteStorage} keeps out, and an author who isn't stored can't be followed here anyway.
 *
 * <p>Once the parent is stored, the activity is handled again, so it goes through every check it would have if the parent
 * had come first. Those find the parent stored, so they don't queue this again.
 */
@Component
class ReplyParentChecks {

    static final TaskDescriptor<ReplyParentJob> TASK = TaskDescriptor.of("reply-parent", ReplyParentJob.class);

    private static final Logger log = LoggerFactory.getLogger(ReplyParentChecks.class);

    private final SchedulerClient scheduler;
    private final StoredActivities activities;
    private final ObjectProvider<InboundActivityHandler> handlers;
    private final ActivityDocuments documents;
    private final TargetFetchTurns turns;
    private final ThreadFetches threads;
    private final RemoteStatuses statuses;
    private final NoteStorage storage;
    private final LocalRecipients recipients;
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
            LocalRecipients recipients,
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
        this.recipients = recipients;
        this.actors = actors;
        this.blocks = blocks;
    }

    /**
     * Queues a check, unless one for the same activity is already queued.
     */
    void queue(InboundActivity activity, URI parent) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Jobs.scheduleAt(scheduler, TASK, new ReplyParentJob(activity.id(), parent.toString(), now, null),
                now.plus(ThreadFetches.PARENT_WAIT), "reply-parent", activity.id());
    }

    @Bean
    static Task<ReplyParentJob> replyParent(ObjectProvider<ReplyParentChecks> replyParents) {
        return Jobs.task(TASK, job -> replyParents.getObject().run(job));
    }

    /**
     * @return what to do with the job, which is to wait if its turn to fetch the parent hasn't come yet
     * @throws IllegalStateException if the parent's server couldn't be reached, which is worth trying again
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
        if (statuses.isStored(parent)) {
            handleAgain(activity.get());
            return Jobs.done();
        }
        if (!threads.isFetchable(parent)) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(parent, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Not fetching {} for a reply to it, since too many fetches are waiting for its server", parent);
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
        if (!recipients.concernsSomeoneHere(note.get().author(), sender)) {
            log.debug("Ignoring {}, whose parent {} isn't by anyone followed here", activity.get().id(), parent);
            return Jobs.done();
        }
        if (storage.storeInThread(note.get().note(), note.get().author()) != NoteStorage.Result.IGNORED) {
            handleAgain(activity.get());
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
