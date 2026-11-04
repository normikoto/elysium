package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Builds the tasks that run jobs, which all retry and park the same way.
 */
public final class Jobs {

    private Jobs() {
    }

    /**
     * Creates a task. The handler runs with no database transaction open, and it can be run again even after it succeeds,
     * so it has to be safe to repeat. A job abandoned by a node that died is picked up again by another one.
     */
    public static <T extends QueuedJob> CustomTask<T> task(TaskDescriptor<T> descriptor, ExecutionHandler<T> handler) {
        return Tasks.custom(descriptor)
                .onFailure(new RetryPolicy<>())
                .execute((instance, context) -> RetryPolicy.parkingExpired(context.getExecution(), Instant.now())
                        ? done()
                        : handler.execute(instance, context));
    }

    /**
     * Finishes a job, which removes it from the queue.
     */
    public static <T> CompletionHandler<T> done() {
        return (complete, operations) -> operations.remove();
    }

    /**
     * Makes a job's ID from what identifies it, so that a repeat can't queue a second job. It's a hash because these values
     * can come from other servers, and PostgreSQL can't index very long ones.
     */
    public static String instanceId(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }

    /**
     * Whether a job has been failing for so long that it shouldn't be tried again.
     */
    public static boolean isOverdue(QueuedJob job, Instant now) {
        return RetryPolicy.isOverdue(job, now);
    }
}
