package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.RemotePollRefresher;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Fetches a remote poll again for its counts, since only some servers send them when they change, as Mastodon does when
 * a poll ends or is viewed. Only the poll's own server can say what it has, so the {@code Question} fetched has to be the
 * stored status's, by the same author. A poll whose options have changed is left for the {@code Update} of that edit.
 */
@Service
class PollRefreshes implements RemotePollRefresher {

    private static final Logger log = LoggerFactory.getLogger(PollRefreshes.class);

    static final TaskDescriptor<PollRefreshJob> TASK = TaskDescriptor.of("poll-refresh", PollRefreshJob.class);

    private final StatusService statuses;
    private final PollService polls;
    private final ActorService actors;
    private final DomainBlocks blocks;
    private final ActivityDocuments documents;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy, because the scheduler needs the refresh task, which needs this
     */
    PollRefreshes(
            StatusService statuses, PollService polls, ActorService actors, DomainBlocks blocks,
            ActivityDocuments documents, @Lazy SchedulerClient scheduler) {
        this.statuses = statuses;
        this.polls = polls;
        this.actors = actors;
        this.blocks = blocks;
        this.documents = documents;
        this.scheduler = scheduler;
    }

    @Override
    public void refreshSoon(UUID statusId) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Jobs.schedule(scheduler, TASK, new PollRefreshJob(statusId, now), TASK.getTaskName(), statusId.toString());
    }

    CompletionHandler<PollRefreshJob> run(PollRefreshJob job) {
        var status = statuses.findStored(job.statusId());
        var author = status.flatMap(found -> actors.findById(found.actorId()))
                .filter(found -> found.isActive() && !actors.isLocal(found));
        if (status.isEmpty() || author.isEmpty() || blocks.isBlocked(author.get().uri().getHost())) {
            return Jobs.done();
        }
        // Noted first, so that a poll whose server fails isn't asked for again on every view.
        polls.markFetched(job.statusId());
        var fetched = AttributedNote.fetch(status.get().uri(), documents)
                .filter(note -> note.author().equals(author.get().uri()));
        if (fetched.isEmpty() || fetched.get().note().poll() == null) {
            log.debug("Not refreshing poll {}, which couldn't be fetched as a Question by its author", status.get().uri());
            return Jobs.done();
        }
        polls.refreshRemote(status.get().uri(), author.get().id(), fetched.get().note().poll());
        return Jobs.done();
    }
}
