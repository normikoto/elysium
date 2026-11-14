package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.net.URI;
import java.time.Instant;

/**
 * Fetches a remote actor's pins, through {@link FeaturedFetch}. A job starts with the actor's {@code featured} collection,
 * can go on to its first page, and queues a job of its own for each status it only names. A job for one status can also
 * come from an {@code Add} of a status that isn't stored.
 *
 * @param actorId  the actor's ID here
 * @param page     the collection's first page, once the collection has named one to fetch, or null
 * @param note     a status to fetch and pin, for a job that does that, or null
 * @param pinnedAt when to say that status was pinned, for a job that fetches one, or null
 * @param turn     the turn {@link TargetFetchTurns} gave it for the next fetch, or null if it hasn't waited for one
 */
record FeaturedFetchJob(String actorId, Instant queuedAt, String page, String note, Instant pinnedAt, Instant turn)
        implements QueuedJob {

    static FeaturedFetchJob of(String actorId, Instant queuedAt) {
        return new FeaturedFetchJob(actorId, queuedAt, null, null, null, null);
    }

    static FeaturedFetchJob ofNote(String actorId, Instant queuedAt, URI note, Instant pinnedAt) {
        return new FeaturedFetchJob(actorId, queuedAt, null, note.toString(), pinnedAt, null);
    }

    FeaturedFetchJob withTurn(Instant turn) {
        return new FeaturedFetchJob(actorId, queuedAt, page, note, pinnedAt, turn);
    }

    FeaturedFetchJob forPage(URI page) {
        return new FeaturedFetchJob(actorId, queuedAt, page.toString(), null, null, null);
    }
}
