package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches the note of a forwarded {@code Create} or {@code Update} from its own server, through {@link ForwardedNotes}.
 *
 * @param noteId   the activity's object, on its actor's server, which is where it's fetched from
 * @param actorId  the activity's actor, who the note has to be attributed to
 * @param type     {@code Create} or {@code Update}. Jobs queued before {@code Update}s were handled don't have it, and are
 *                 for a {@code Create}.
 * @param queuedAt when the first forwarded copy arrived, which is also stored as when the activity did
 * @param turn     the turn {@link TargetFetchTurns} gave it to fetch the note, or null if it hasn't waited for one
 * @param sender   who {@link TargetFetchTurns} charges for the fetch, which is the server that forwarded the activity, or
 *                 null for a job queued before senders were counted
 */
record ForwardedNoteJob(String noteId, String actorId, String type, Instant queuedAt, Instant turn, String sender)
        implements QueuedJob {

    ForwardedNoteJob withTurn(Instant turn) {
        return new ForwardedNoteJob(noteId, actorId, type, queuedAt, turn, sender);
    }

    boolean isUpdate() {
        return "Update".equals(type);
    }
}
