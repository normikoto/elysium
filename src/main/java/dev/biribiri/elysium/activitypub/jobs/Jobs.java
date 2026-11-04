package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import dev.biribiri.elysium.activitypub.signature.Digests;

import java.time.Instant;
import java.util.function.Function;

/**
 * Builds the tasks that run jobs, which all retry and park the same way.
 */
public final class Jobs {

    private Jobs() {
    }

    /**
     * Creates a task. The handler is given the job's data. It runs with no database transaction open, and it can be run again even after it succeeds,
     * so it has to be safe to repeat. A job abandoned by a node that died is picked up again by another one.
     */
    public static <T extends QueuedJob> CustomTask<T> task(TaskDescriptor<T> descriptor, Function<T, CompletionHandler<T>> handler) {
        return Tasks.custom(descriptor)
                .onFailure(new RetryPolicy<>())
                .execute((instance, context) -> RetryPolicy.parkingExpired(context.getExecution(), Instant.now())
                        ? done()
                        : handler.apply(instance.getData()));
    }

    /**
     * Finishes a job, which removes it from the queue.
     */
    public static <T> CompletionHandler<T> done() {
        return (complete, operations) -> operations.remove();
    }

    /**
     * Queues a job to run now, unless one with the same ID is already queued.
     *
     * @param idParts what identifies the job, so that a repeat can't queue a second one
     */
    public static <T extends QueuedJob> void schedule(SchedulerClient scheduler, TaskDescriptor<T> task, T job, String... idParts) {
        scheduler.scheduleIfNotExists(task.instance(instanceId(idParts)).data(job).scheduledTo(job.queuedAt()));
    }

    /**
     * Queues a job to run now, for when something has changed since it was last queued. A job that's already queued runs now
     * instead of when it was going to, including one that's been set aside after failing. One that's running at the moment
     * isn't touched, so it has to look for a change itself when it finishes.
     *
     * @param idParts what identifies the job
     */
    public static <T extends QueuedJob> void scheduleAgain(SchedulerClient scheduler, TaskDescriptor<T> task, T job, String... idParts) {
        var id = task.instanceId(instanceId(idParts));
        var existing = scheduler.getScheduledExecution(id);
        if (existing.isEmpty()) {
            schedule(scheduler, task, job, idParts);
        } else if (!existing.get().isPicked()) {
            scheduler.reschedule(id, job.queuedAt());
        }
    }

    /**
     * Makes a job's ID. It's a hash because these values can come from other servers, and PostgreSQL can't index very long
     * ones.
     */
    static String instanceId(String... parts) {
        return Digests.sha256Hex(String.join("\n", parts));
    }
}
