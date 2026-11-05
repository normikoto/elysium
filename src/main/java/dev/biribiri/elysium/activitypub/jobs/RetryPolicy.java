package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides what happens to a job that failed. It retries temporary failures with increasing delays, and then parks the job.
 *
 * <p>Parking keeps the job in the queue, set to run far in the future, so that admins can see why it failed and retry or
 * delete it. The library's own limit on retries would remove the job instead. When its time comes with nobody having
 * touched it, {@link #parkingExpired} is true and it's dropped without running.
 *
 * <p>Delays come from the job's age and not from its failure count, which doesn't last: the library resets it whenever a
 * job is rescheduled without failing, as a job is when its server is backing off. A delay equal to the age doubles the gap
 * after each try.
 */
final class RetryPolicy<T extends QueuedJob> implements FailureHandler<T> {

    static final Duration GIVE_UP_AFTER = Duration.ofDays(3);
    static final Duration PARKED_FOR = Duration.ofDays(30);

    /**
     * How long a job can last without an admin retrying it, which is failing until it's given up on and then being parked.
     */
    static final Duration LONGEST_LIFE = GIVE_UP_AFTER.plus(PARKED_FOR);

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);
    private static final Duration MIN_DELAY = Duration.ofMinutes(1);
    private static final Duration MAX_DELAY = Duration.ofHours(6);

    @Override
    public void onFailure(ExecutionComplete complete, ExecutionOperations<T> operations) {
        var execution = complete.getExecution();
        var job = (QueuedJob) execution.taskInstance.getData();
        var now = complete.getTimeDone();
        var permanent = complete.getCause().filter(PermanentJobFailure.class::isInstance).isPresent();
        if (permanent || job.isOverdue(now)) {
            log.warn("Giving up on job {}: it stays in the queue for {} days so that an admin can look at it",
                    execution.taskInstance.getTaskAndInstance(), PARKED_FOR.toDays());
            operations.reschedule(complete, now.plus(PARKED_FOR));
        } else {
            operations.reschedule(complete, now.plus(jitter(delayFor(job, now))));
        }
    }

    /**
     * Whether this run is a parked job's time running out, and not an admin retrying it. Retries are never this far apart.
     */
    static boolean parkingExpired(Execution execution, Instant now) {
        return execution.consecutiveFailures > 0
                && execution.lastFailure != null
                && !execution.lastFailure.plus(PARKED_FOR).isAfter(now);
    }

    static Duration delayFor(QueuedJob job, Instant now) {
        var age = Duration.between(job.queuedAt(), now);
        if (age.compareTo(MIN_DELAY) < 0) {
            return MIN_DELAY;
        }
        return age.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : age;
    }

    /**
     * Spreads out jobs that failed together, so that they don't all try again at the same moment.
     */
    private static Duration jitter(Duration delay) {
        return delay.plusMillis(ThreadLocalRandom.current().nextLong(delay.toMillis() / 5 + 1));
    }
}
