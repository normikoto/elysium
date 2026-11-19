package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches a forwarded activity from its origin, through {@link ForwardedActivities}.
 *
 * @param activityId    the activity's {@code id}, which is where it's fetched from
 * @param queuedAt      when the first forwarded copy arrived, which is also stored as when the activity did
 * @param deletedObject for a {@code Delete}, the object it says is deleted, which is checked with its own server if the
 *                      origin doesn't serve the activity, and otherwise null
 * @param createdObject for a {@code Create}, its object, which is fetched from its own server if the origin doesn't serve
 *                      the activity, and otherwise null. Jobs queued before this was added don't have it.
 * @param updatedObject the same, for an {@code Update} of a note
 * @param actorId       for a {@code Delete}, a {@code Create}, or an {@code Update} of a note, its actor, and otherwise
 *                      null
 * @param turn          the turn {@link TargetFetchTurns} gave it to fetch from the origin, or null if it hasn't waited for
 *                      one
 */
record ForwardedJob(
        String activityId,
        Instant queuedAt,
        String deletedObject,
        String createdObject,
        String updatedObject,
        String actorId,
        Instant turn)
        implements QueuedJob {

    ForwardedJob withTurn(Instant turn) {
        return new ForwardedJob(activityId, queuedAt, deletedObject, createdObject, updatedObject, actorId, turn);
    }
}
