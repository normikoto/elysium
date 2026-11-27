package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches the parent of the replies waiting in {@link ReplyParentJob}s, once for all of them, through
 * {@link ReplyParentChecks}.
 *
 * @param parentUri where the parent is fetched from
 * @param turn      the turn {@link TargetFetchTurns} gave it to fetch the parent, or null if it hasn't waited for one
 */
record ParentFetchJob(String parentUri, Instant queuedAt, Instant turn) implements QueuedJob {

    ParentFetchJob withTurn(Instant turn) {
        return new ParentFetchJob(parentUri, queuedAt, turn);
    }
}
