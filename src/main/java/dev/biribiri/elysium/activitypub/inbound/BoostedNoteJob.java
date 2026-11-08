package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches the status that a stored {@code Announce} boosts, through {@link Boosts}.
 *
 * @param announceId the {@code Announce}, which is read again from the stored activities, so that this only holds IDs
 * @param turn       the turn {@link TargetFetchTurns} gave it to fetch the status, or null if it hasn't waited for one
 */
record BoostedNoteJob(String announceId, Instant queuedAt, Instant turn) implements QueuedJob {

    BoostedNoteJob withTurn(Instant turn) {
        return new BoostedNoteJob(announceId, queuedAt, turn);
    }
}
