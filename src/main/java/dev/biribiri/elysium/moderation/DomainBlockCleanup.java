package dev.biribiri.elysium.moderation;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.actor.ActorDataRemoval;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.jobs.Jobs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Removes what's stored by and about the actors on a blocked server, through each module's {@link ActorDataRemoval}.
 * Actors themselves are kept, because other things can refer to them, and nothing is sent to them. It's a job because
 * there can be a lot to remove.
 */
@Service
class DomainBlockCleanup {

    private static final TaskDescriptor<DomainBlockCleanupJob> TASK =
            TaskDescriptor.of("domain-block-cleanup", DomainBlockCleanupJob.class);

    /**
     * A {@code Follow} that was checked just before the block committed can be saved after the first job has run. Taking a
     * lock for this would be something for everything that stores remote data to remember, and this is the one place that
     * a second look is needed.
     */
    private static final Duration SECOND_SWEEP_DELAY = Duration.ofMinutes(5);

    private final DomainBlocks blocks;
    private final ActorService actors;
    private final List<ActorDataRemoval> removals;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    DomainBlockCleanup(DomainBlocks blocks, ActorService actors, List<ActorDataRemoval> removals,
                       @Lazy SchedulerClient scheduler) {
        this.blocks = blocks;
        this.actors = actors;
        this.removals = removals;
        this.scheduler = scheduler;
    }

    @Bean
    static Task<DomainBlockCleanupJob> cleanUpAfterDomainBlock(ObjectProvider<DomainBlockCleanup> cleanup) {
        return Jobs.task(TASK, job -> cleanup.getObject().run(job));
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

    /**
     * Stops if the block has since been removed, since anyone following from that server is allowed to again. Otherwise this
     * looks at every block rather than only this one, so that matching stays in one place.
     */
    CompletionHandler<DomainBlockCleanupJob> run(DomainBlockCleanupJob job) {
        if (blocks.find(job.blockId()).isEmpty()) {
            return Jobs.done();
        }
        blocks.blockedHosts(actors.remoteDomains())
                .forEach(domain -> removals.forEach(removal -> removal.removeForDomain(domain)));
        return Jobs.done();
    }
}
