package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import dev.biribiri.elysium.activitypub.signature.Digests;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * Builds the tasks that run jobs, which all retry and park the same way.
 */
public final class Jobs {

    /**
     * A little longer than a job can last, so that they're still there if it's retried at the last moment.
     */
    static final Duration ACTIVITIES_KEPT = RetryPolicy.LONGEST_LIFE.plusDays(1);

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
     * What to do with a job whose activity isn't stored. An outbound one can have been cancelled, and then the job just
     * finishes. But an admin can retry a job for longer than activities are kept, so one that's old enough to have lost its
     * activity fails, which shows them why there's nothing to do. Failing parks it, and it's removed when that runs out.
     *
     * @throws PermanentJobFailure if the job is old enough that its activity could have been cleaned up
     */
    public static <T extends QueuedJob> CompletionHandler<T> activityGone(T job) {
        if (Duration.between(job.queuedAt(), Instant.now()).compareTo(RetryPolicy.LONGEST_LIFE) > 0) {
            throw new PermanentJobFailure("Its activity is gone, so there's nothing to do. Activities are kept for "
                    + ACTIVITIES_KEPT.toDays() + " days, and one being sent can also have been cancelled");
        }
        return done();
    }

    /**
     * Queues a job to run now, unless one with the same ID is already queued.
     *
     * @param idParts what identifies the job, so that a repeat can't queue a second one
     */
    public static <T extends QueuedJob> void schedule(SchedulerClient scheduler, TaskDescriptor<T> task, T job, String... idParts) {
        schedule(scheduler, task, job, job.queuedAt(), idParts);
    }

    /**
     * Queues a job to run at a given time, unless one with the same ID is already queued. Its age still counts from when it was
     * queued, so that it's retried and given up on the same as any other.
     *
     * @param idParts what identifies the job, so that a repeat can't queue a second one
     */
    public static <T extends QueuedJob> void schedule(
            SchedulerClient scheduler, TaskDescriptor<T> task, T job, Instant runAt, String... idParts) {
        scheduler.scheduleIfNotExists(task.instance(instanceId(idParts)).data(job).scheduledTo(runAt));
    }

    /**
     * Makes a job's ID. It's a hash because these values can come from other servers, and PostgreSQL can't index very long
     * ones.
     */
    public static String instanceId(String... parts) {
        return Digests.sha256Hex(String.join("\n", parts));
    }
}
