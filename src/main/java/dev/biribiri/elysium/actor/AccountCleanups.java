package dev.biribiri.elysium.actor;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.jobs.Jobs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Removes what every module stores for a deleted actor, or what a suspended remote one loses, through each module's
 * {@link ActorDataRemoval}. It's a job because there can be a lot to remove.
 *
 * <p>Whoever reacts to the deletion or suspension queues this, rather than it listening for the event itself, because
 * telling other servers about a deleted account reads the follows that this removes.
 */
@Service
public class AccountCleanups {

    private static final TaskDescriptor<AccountCleanupJob> TASK = TaskDescriptor.of("account-cleanup", AccountCleanupJob.class);

    /**
     * A {@code Follow} or a status that was checked just before the change committed can be saved after the first job has run.
     */
    private static final Duration SECOND_SWEEP_DELAY = Duration.ofMinutes(5);

    private final ActorService actors;
    private final List<ActorDataRemoval> removals;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    AccountCleanups(ActorService actors, List<ActorDataRemoval> removals, @Lazy SchedulerClient scheduler) {
        this.actors = actors;
        this.removals = removals;
        this.scheduler = scheduler;
    }

    /**
     * Static, and given this class lazily, because this class needs the scheduler, which needs the task. What it's given
     * is a proxy, so the method it calls can't be private, or it would run on the proxy, which has none of the fields.
     */
    @Bean
    static Task<AccountCleanupJob> cleanUpAfterAccount(ObjectProvider<AccountCleanups> cleanups) {
        return Jobs.task(TASK, job -> cleanups.getObject().run(job));
    }

    /**
     * Queues the cleanup for an actor that's been deleted, or a remote one that's been suspended. Queuing it again for the
     * same occasion has no effect while its jobs are queued.
     *
     * @param occasion what happened, which tells this apart from the cleanup for another time it happened
     */
    public void queue(UUID actorId, String occasion) {
        var now = Instant.now();
        var job = new AccountCleanupJob(actorId, now);
        Jobs.schedule(scheduler, TASK, job, actorId.toString(), occasion, "first");
        Jobs.scheduleAt(scheduler, TASK, job, now.plus(SECOND_SWEEP_DELAY), actorId.toString(), occasion, "second");
    }

    CompletionHandler<AccountCleanupJob> run(AccountCleanupJob job) {
        actors.findById(job.actorId()).ifPresent(actor -> {
            if (actor.isDeleted()) {
                removals.forEach(removal -> removal.removeForDeleted(actor.id()));
            } else if (actor.isSuspended() && !actors.isLocal(actor)) {
                removals.forEach(removal -> removal.removeForSuspendedRemote(actor.id()));
            }
        });
        return Jobs.done();
    }
}
