package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Duration;
import java.time.Instant;

/**
 * How long the activities that jobs send and handle are kept, and what a job does once its activity is gone.
 */
public final class ActivityJobs {

    /**
     * A little longer than a job can last, so that they're still there if it's retried at the last moment.
     */
    public static final Duration ACTIVITIES_KEPT = Jobs.LONGEST_LIFE.plusDays(1);

    private ActivityJobs() {
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
        return Jobs.done();
    }

    /**
     * Whether a job has been waiting for so long that its activity could have been cleaned up, for one that's waiting on purpose
     * and should give up rather than fail in {@link #activityGone}.
     */
    public static boolean hasOutlivedItsActivity(QueuedJob job) {
        return Duration.between(job.queuedAt(), Instant.now()).compareTo(Jobs.LONGEST_LIFE) > 0;
    }
}
