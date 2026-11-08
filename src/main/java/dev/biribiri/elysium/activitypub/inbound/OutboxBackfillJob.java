package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.net.URI;
import java.time.Instant;

/**
 * Fetches recent statuses from a remote actor's outbox, through {@link OutboxBackfill}.
 *
 * @param actorId the actor's ID here
 * @param page    the outbox's first page, once the outbox has named one to fetch, or null to fetch the outbox
 * @param turn    the turn {@link TargetFetchTurns} gave it for the next fetch, or null if it hasn't waited for one
 */
record OutboxBackfillJob(String actorId, Instant queuedAt, String page, Instant turn) implements QueuedJob {

    OutboxBackfillJob withTurn(Instant turn) {
        return new OutboxBackfillJob(actorId, queuedAt, page, turn);
    }

    OutboxBackfillJob forPage(URI page) {
        return new OutboxBackfillJob(actorId, queuedAt, page.toString(), null);
    }
}
