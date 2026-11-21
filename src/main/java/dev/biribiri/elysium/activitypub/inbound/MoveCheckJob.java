package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;

/**
 * Checks that the account a {@code Move} names lists the account that sent it, through {@link MoveChecks}.
 *
 * @param activityId the {@code Move}
 * @param actorId    the account that moved, which is the {@code Move}'s actor
 * @param targetId   the account it says it moved to
 * @param turn       the turn {@link TargetFetchTurns} gave it to fetch the target, or null if it hasn't waited for one
 */
record MoveCheckJob(String activityId, String actorId, String targetId, Instant queuedAt, Instant turn)
        implements QueuedJob {

    MoveCheckJob withTurn(Instant turn) {
        return new MoveCheckJob(activityId, actorId, targetId, queuedAt, turn);
    }
}
