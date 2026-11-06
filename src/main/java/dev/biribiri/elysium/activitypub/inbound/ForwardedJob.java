package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches a forwarded activity from its origin, through {@link ForwardedActivities}.
 *
 * @param activityId the activity's {@code id}, which is where it's fetched from
 */
record ForwardedJob(String activityId, Instant queuedAt) implements QueuedJob {
}
