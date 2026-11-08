package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.net.URI;
import java.time.Instant;

/**
 * Fetches recent statuses from a remote actor's outbox, through {@link OutboxBackfill}. A job starts with the outbox, can go
 * on to its first page, and queues a job of its own for each status the page only names.
 *
 * @param actorId the actor's ID here
 * @param page    the outbox's first page, once the outbox has named one to fetch, or null
 * @param note    a status that the first page only names, for a job that fetches it, or null
 * @param turn    the turn {@link TargetFetchTurns} gave it for the next fetch, or null if it hasn't waited for one
 */
record OutboxBackfillJob(String actorId, Instant queuedAt, String page, String note, Instant turn) implements QueuedJob {

    static OutboxBackfillJob of(String actorId, Instant queuedAt) {
        return new OutboxBackfillJob(actorId, queuedAt, null, null, null);
    }

    OutboxBackfillJob withTurn(Instant turn) {
        return new OutboxBackfillJob(actorId, queuedAt, page, note, turn);
    }

    OutboxBackfillJob forPage(URI page) {
        return new OutboxBackfillJob(actorId, queuedAt, page.toString(), null, null);
    }

    OutboxBackfillJob forNote(URI note) {
        return new OutboxBackfillJob(actorId, queuedAt, null, note.toString(), null);
    }
}
