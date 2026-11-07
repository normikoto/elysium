package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches a forwarded activity from its origin, through {@link ForwardedActivities}.
 *
 * @param activityId the activity's {@code id}, which is where it's fetched from
 * @param queuedAt   when the first forwarded copy arrived, which is also stored as when the activity did
 * @param deletedObject for a {@code Delete}, the object it says is deleted, which is checked with its own server if the
 *                      origin doesn't serve the activity, and otherwise null
 * @param actorId       for a {@code Delete}, its actor, and otherwise null
 */
record ForwardedJob(String activityId, Instant queuedAt, String deletedObject, String actorId) implements QueuedJob {
}
