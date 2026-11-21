package dev.biribiri.elysium.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Builds the tasks that run jobs, which all retry and park the same way.
 */
public final class Jobs {

    /**
     * How long a job can last without an admin retrying it, which is failing until it's given up on and then being parked.
     * What a job needs has to be kept at least this long.
     */
    public static final Duration LONGEST_LIFE = RetryPolicy.LONGEST_LIFE;

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

    /**
     * The shortest wait after a {@link BusyException}. Fetches end within the HTTP client's deadline, and most within a
     * second or two.
     */
    static final Duration MIN_HOST_WAIT = Duration.ofSeconds(5);

    /**
     * The longest wait after a {@link BusyException}, which a job reaches once it's 10 minutes old.
     */
    static final Duration MAX_HOST_WAIT = Duration.ofMinutes(1);

    private static final ScopedValue<Boolean> JOB_THREAD = ScopedValue.newInstance();

    private Jobs() {
    }

    /**
     * Makes threads that {@link #isJobThread} knows as the scheduler's, for every task it runs, including recurring ones
     * that aren't built here.
     */
    static ThreadFactory markingJobThreads(ThreadFactory threads) {
        return work -> threads.newThread(() -> ScopedValue.where(JOB_THREAD, true).run(work));
    }

    /**
     * Whether this is one of the scheduler's threads, which there are only a few of, so that what would wait on a busy server
     * for someone who's waiting can leave that to the job instead.
     */
    public static boolean isJobThread() {
        return JOB_THREAD.isBound();
    }

    /**
     * Creates a task. The handler is given the job's data. It runs with no database transaction open, and it can be run again even after it succeeds,
     * so it has to be safe to repeat. A job abandoned by a node that died is picked up again by another one.
     *
     * <p>A handler that finds a host busy throws {@link BusyException}, or returns {@link #waitForHost}, and the job waits
     * and then runs again from the start. Failing instead would put the job a minute or more behind, and the failures would bring it closer to being given up
     * on, though its server did nothing wrong.
     */
    public static <T extends QueuedJob> CustomTask<T> task(TaskDescriptor<T> descriptor, Function<T, CompletionHandler<T>> handler) {
        return Tasks.custom(descriptor)
                .onFailure(new RetryPolicy<>())
                .execute((instance, context) -> {
                    if (RetryPolicy.parkingExpired(context.getExecution(), Instant.now())) {
                        return done();
                    }
                    var job = instance.getData();
                    try {
                        return handler.apply(job);
                    } catch (BusyException e) {
                        return waitUntil(hostWaitEnds(job, Instant.now()));
                    }
                });
    }

    /**
     * Waits for a busy host, as {@link #task} does, for a job that has to keep something it was given in this run, such as a
     * turn from {@code TargetFetchTurns}. Taking a new turn after each wait would use up the host's rate without fetching
     * anything.
     *
     * @param keeping the job's data to keep, given when the job runs again
     */
    public static <T extends QueuedJob> CompletionHandler<T> waitForHost(T job, Function<Instant, T> keeping) {
        var time = hostWaitEnds(job, Instant.now());
        return waitUntil(time, keeping.apply(time));
    }

    /**
     * A tenth of the job's age, within {@link #MIN_HOST_WAIT} and {@link #MAX_HOST_WAIT}. A large backlog for one host would
     * otherwise run every few seconds for as long as the host stays busy, taking threads from jobs for other hosts. The
     * job's age needs nothing stored, unlike a count of its waits.
     */
    static Instant hostWaitEnds(QueuedJob job, Instant now) {
        var wait = Duration.between(job.queuedAt(), now).dividedBy(10);
        if (wait.compareTo(MIN_HOST_WAIT) < 0) {
            wait = MIN_HOST_WAIT;
        } else if (wait.compareTo(MAX_HOST_WAIT) > 0) {
            wait = MAX_HOST_WAIT;
        }
        // Spreads out jobs that found the host busy together.
        return now.plus(wait).plusMillis(ThreadLocalRandom.current().nextLong(wait.toMillis() / 5 + 1));
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
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
