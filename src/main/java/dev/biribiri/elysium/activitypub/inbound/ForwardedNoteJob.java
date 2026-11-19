package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches the note of a forwarded {@code Create} from its own server, through {@link ForwardedNotes}.
 *
 * @param noteId   the {@code Create}'s object, on its actor's server, which is where it's fetched from
 * @param actorId  the {@code Create}'s actor, who the note has to be attributed to
 * @param queuedAt when the first forwarded copy arrived, which is also stored as when the {@code Create} did
 * @param turn     the turn {@link TargetFetchTurns} gave it to fetch the note, or null if it hasn't waited for one
 */
record ForwardedNoteJob(String noteId, String actorId, Instant queuedAt, Instant turn) implements QueuedJob {

    ForwardedNoteJob withTurn(Instant turn) {
        return new ForwardedNoteJob(noteId, actorId, queuedAt, turn);
    }
}
