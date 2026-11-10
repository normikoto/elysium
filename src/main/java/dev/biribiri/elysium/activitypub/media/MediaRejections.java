package dev.biribiri.elysium.activitypub.media;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.ActorMediaAllowed;
import dev.biribiri.elysium.actor.ActorMediaRejected;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.moderation.DomainMediaAllowed;
import dev.biribiri.elysium.moderation.DomainMediaRejected;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Applies an admin's rejection of a server's or an account's media to the attachments already stored: they're marked as
 * rejected, which shows them as links, and the copies of them are removed. New attachments from them are stored as
 * rejected to begin with. It's a job, because a server can have a lot stored.
 *
 * <p>Allowing media again only marks them as not rejected. They stay links, since copies aren't downloaded again.
 */
@Service
public class MediaRejections {

    static final TaskDescriptor<MediaRejectionJob> TASK = TaskDescriptor.of("media-rejection", MediaRejectionJob.class);

    /**
     * A download that started just before the rejection can finish after the first sweep, so a second one removes it.
     */
    private static final Duration SECOND_SWEEP_DELAY = Duration.ofMinutes(5);

    private final SchedulerClient scheduler;
    private final MediaAttachments attachments;
    private final DomainBlocks blocks;
    private final ActorService actors;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    MediaRejections(@Lazy SchedulerClient scheduler, MediaAttachments attachments, DomainBlocks blocks,
                    ActorService actors) {
        this.scheduler = scheduler;
        this.attachments = attachments;
        this.blocks = blocks;
        this.actors = actors;
    }

    @ApplicationModuleListener
    public void on(DomainMediaRejected event) {
        queue(new MediaRejectionJob(event.blockId(), null, Instant.now()), "block", event.blockId().toString());
    }

    @ApplicationModuleListener
    public void on(ActorMediaRejected event) {
        queue(new MediaRejectionJob(null, event.actorId(), Instant.now()), "actor", event.actorId().toString());
    }

    /**
     * Attachments that another block still covers stay rejected.
     */
    @ApplicationModuleListener
    public void on(DomainMediaAllowed event) {
        attachments.allowForDomain(event.domain());
    }

    @ApplicationModuleListener
    public void on(ActorMediaAllowed event) {
        attachments.allowForAccount(event.actorId());
    }

    /**
     * Doing this again has no effect while its jobs are queued, and only repeats a safe job after that. The time it was
     * queued is part of its ID, so that rejecting again after allowing queues new jobs.
     */
    private void queue(MediaRejectionJob job, String kind, String id) {
        var at = job.queuedAt().toString();
        Jobs.schedule(scheduler, TASK, job, kind, id, at, "first");
        Jobs.scheduleAt(scheduler, TASK, job, job.queuedAt().plus(SECOND_SWEEP_DELAY), kind, id, at, "second");
    }

    /**
     * Looks at the filter now, so that one that's been lifted since does nothing.
     */
    CompletionHandler<MediaRejectionJob> run(MediaRejectionJob job) {
        if (job.blockId() != null) {
            blocks.find(job.blockId())
                    .filter(DomainBlock::rejectsMedia)
                    .ifPresent(block -> attachments.rejectForDomain(block.domain()));
        } else if (job.actorId() != null && actors.isMediaRejected(job.actorId())) {
            attachments.rejectForAccount(job.actorId());
        }
        return Jobs.done();
    }

    /**
     * For a download that's about to start, so that one from a server or account whose media is rejected doesn't.
     */
    boolean isRejected(UUID authorId, URI authorUri) {
        return blocks.rejectsMedia(authorUri.getHost()) || actors.isMediaRejected(authorId);
    }
}
