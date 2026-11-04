package dev.biribiri.elysium.activitypub.jobs;

import java.time.Instant;

/**
 * Data for a job, which is stored as JSON and shown to admins. It should hold IDs rather than the contents of activities,
 * because admins shouldn't see private posts, and the job history keeps a copy after the post is deleted.
 */
public interface QueuedJob {

    /**
     * When the job was first queued, which retries are timed from.
     */
    Instant queuedAt();

    /**
     * Whether this job has been failing for so long that it shouldn't be tried again.
     */
    default boolean isOverdue(Instant now) {
        return !queuedAt().plus(RetryPolicy.GIVE_UP_AFTER).isAfter(now);
    }
}
