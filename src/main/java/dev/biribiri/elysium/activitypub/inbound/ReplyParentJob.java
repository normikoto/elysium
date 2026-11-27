package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;

/**
 * Finds the parent of a reply that was addressed to nobody here, through {@link ReplyParentChecks}.
 *
 * @param activityId the {@code Create} or {@code Update} of the reply, which is handled again once the parent is stored
 * @param parentUri  the reply's {@code inReplyTo}, which is where it's fetched from
 * @param turn       the turn {@link TargetFetchTurns} gave it to fetch the parent, or null if it hasn't waited for one
 */
record ReplyParentJob(String activityId, String parentUri, Instant queuedAt, Instant turn) implements QueuedJob {

    ReplyParentJob withTurn(Instant turn) {
        return new ReplyParentJob(activityId, parentUri, queuedAt, turn);
    }
}
