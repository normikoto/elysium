package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Checks with the object's server that a {@code Delete} is true, through {@link DeletionChecks}.
 *
 * @param objectId the deleted object, which is where it's fetched from
 * @param actorId  the {@code Delete}'s actor, which a status has to be by to be removed
 * @param turn     the turn {@link TargetFetchTurns} gave it to fetch the object, or null if it hasn't waited for one
 */
record DeletionCheckJob(String objectId, String actorId, Instant queuedAt, Instant turn) implements QueuedJob {

    DeletionCheckJob(String objectId, String actorId, Instant queuedAt) {
        this(objectId, actorId, queuedAt, null);
    }

    DeletionCheckJob withTurn(Instant turn) {
        return new DeletionCheckJob(objectId, actorId, queuedAt, turn);
    }
}
