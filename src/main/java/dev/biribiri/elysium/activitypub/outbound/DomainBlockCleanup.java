package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.moderation.DomainBlocked;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Queues the removal of what's stored from a blocked server. It's a job because there can be a lot to remove.
 */
@Service
public class DomainBlockCleanup {

    static final TaskDescriptor<DomainBlockCleanupJob> TASK =
            TaskDescriptor.of("domain-block-cleanup", DomainBlockCleanupJob.class);

    /**
     * A {@code Follow} that was checked just before the block committed can be saved after the first job has run. Taking a
     * lock for this would be something for everything that stores remote data to remember, and this is the one place that
     * a second look is needed.
     */
    private static final Duration SECOND_SWEEP_DELAY = Duration.ofMinutes(5);

    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    DomainBlockCleanup(@Lazy SchedulerClient scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Doing this again for the same block has no effect while its jobs are queued, and only repeats a safe job after that.
     */
    @ApplicationModuleListener
    public void on(DomainBlocked event) {
        var id = event.blockId().toString();
        var now = Instant.now();
        var job = new DomainBlockCleanupJob(event.blockId(), now);
        Jobs.schedule(scheduler, TASK, job, id, "first");
        Jobs.scheduleAt(scheduler, TASK, job, now.plus(SECOND_SWEEP_DELAY), id, "second");
    }
}
