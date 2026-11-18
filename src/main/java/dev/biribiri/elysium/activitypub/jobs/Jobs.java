package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import dev.biribiri.elysium.activitypub.signature.Digests;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Builds the tasks that run jobs, which all retry and park the same way.
 */
public final class Jobs {

    /**
     * A little longer than a job can last, so that they're still there if it's retried at the last moment.
     */
    static final Duration ACTIVITIES_KEPT = RetryPolicy.LONGEST_LIFE.plusDays(1);

    /**
     * How long a job that keeps failing is retried for, before it's parked.
     */
    public static final Duration RETRIED_FOR = RetryPolicy.GIVE_UP_AFTER;

    /**
     * Runs that ended in {@link #waitUntil}, for {@link JobHistory} to leave out. The scheduler hands the same {@link ExecutionComplete} to
     * the completion handler and then to its listeners, and the class doesn't override {@code equals}, so this finds it by
     * identity. It's weak so that nothing builds up when there's no history.
     */
    private static final Set<ExecutionComplete> WAITS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

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
     * Runs a job again later, without counting as a failure, such as to carry on with the next part of its work.
     */
    public static <T> CompletionHandler<T> rescheduleAt(Instant time) {
        return (complete, operations) -> operations.reschedule(complete, time);
    }

    /**
     * Like {@link #rescheduleAt(Instant)}, replacing the job's data, such as to remember where it got to.
     */
    public static <T> CompletionHandler<T> rescheduleAt(Instant time, T data) {
        return (complete, operations) -> operations.reschedule(complete, time, data);
    }

    /**
     * Like {@link #rescheduleAt(Instant)}, for a job that did nothing but find it has to wait, such as for a busy server. The
     * run isn't kept in the job history, since a job waiting for a busy server can run several times a second, and the job's
     * still listed with when it runs next.
     */
    public static <T> CompletionHandler<T> waitUntil(Instant time) {
        return (complete, operations) -> {
            operations.reschedule(complete, time);
            WAITS.add(complete);
        };
    }

    /**
     * Like {@link #waitUntil(Instant)}, replacing the job's data, such as to remember a turn it was given.
     */
    public static <T> CompletionHandler<T> waitUntil(Instant time, T data) {
        return (complete, operations) -> {
            operations.reschedule(complete, time, data);
            WAITS.add(complete);
        };
    }

    /**
     * Whether a run ended by {@link #waitUntil}, which this only answers once.
     */
    static boolean onlyWaited(ExecutionComplete complete) {
        return WAITS.remove(complete);
    }

    /**
     * What to do with a job whose activity isn't stored. An outbound one can have been cancelled, and then the job just
     * finishes. But an admin can retry a job for longer than activities are kept, so one that's old enough to have lost its
     * activity fails, which shows them why there's nothing to do. Failing parks it, and it's removed when that runs out.
     *
     * @throws PermanentJobFailure if the job is old enough that its activity could have been cleaned up
     */
    public static <T extends QueuedJob> CompletionHandler<T> activityGone(T job) {
        if (hasOutlivedItsActivity(job)) {
            throw new PermanentJobFailure("Its activity is gone, so there's nothing to do. Activities are kept for "
                    + ACTIVITIES_KEPT.toDays() + " days, and one being sent can also have been cancelled");
        }
        return done();
    }

    /**
     * Whether a job has been waiting for so long that its activity could have been cleaned up, for one that's waiting on purpose
     * and should give up rather than fail in {@link #activityGone}.
     */
    public static boolean hasOutlivedItsActivity(QueuedJob job) {
        return Duration.between(job.queuedAt(), Instant.now()).compareTo(RetryPolicy.LONGEST_LIFE) > 0;
    }

    /**
     * Queues a job to run now, unless one with the same ID is already queued.
     *
     * @param idParts what identifies the job, so that a repeat can't queue a second one
     */
    public static <T extends QueuedJob> void schedule(SchedulerClient scheduler, TaskDescriptor<T> task, T job, String... idParts) {
        scheduleAt(scheduler, task, job, job.queuedAt(), idParts);
    }

    /**
     * Like {@link #schedule}, for a job that shouldn't run until later.
     */
    public static <T extends QueuedJob> void scheduleAt(
            SchedulerClient scheduler, TaskDescriptor<T> task, T job, Instant runAt, String... idParts) {
        scheduler.scheduleIfNotExists(task.instance(instanceId(idParts)).data(job).scheduledTo(runAt));
    }

    /**
     * Like {@link #schedule}, for a job that's one of a group, so that {@link #isGroupQueued} can tell whether any of the
     * group is left.
     *
     * @param group what the group's jobs share, which has to be short and can't come from another server, since it isn't
     *              hashed
     */
    public static <T extends QueuedJob> void scheduleInGroup(
            SchedulerClient scheduler, TaskDescriptor<T> task, T job, String group, String... idParts) {
        scheduler.scheduleIfNotExists(
                task.instance(group + "/" + instanceId(idParts)).data(job).scheduledTo(job.queuedAt()));
    }

    /**
     * Whether any job of a group from {@link #scheduleInGroup} is queued, including one that's failing or parked.
     */
    public static boolean isGroupQueued(JdbcClient jdbc, TaskDescriptor<?> task, String group) {
        // A range in the C collation, which scheduled_tasks_instance_idx covers, finds the group's IDs as a prefix would,
        // since '0' comes right after '/'. The primary key's collation doesn't sort that way, so it can't do this.
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM scheduled_tasks
                                       WHERE task_name = :task
                                         AND task_instance COLLATE "C" >= :from AND task_instance COLLATE "C" < :to)
                        """)
                .param("task", task.getTaskName())
                .param("from", group + "/")
                .param("to", group + "0")
                .query(Boolean.class)
                .single();
    }

    /**
     * Whether a job with this ID is queued, including one that's failing or parked.
     */
    public static boolean isQueued(SchedulerClient scheduler, TaskDescriptor<?> task, String... idParts) {
        return scheduler.getScheduledExecution(task.instanceId(instanceId(idParts))).isPresent();
    }

    /**
     * Makes a job's ID. It's a hash because these values can come from other servers, and PostgreSQL can't index very long
     * ones.
     */
    static String instanceId(String... parts) {
        return Digests.sha256Hex(String.join("\n", parts));
    }
}
