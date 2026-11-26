package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.net.URI;
import java.time.Instant;

/**
 * Fetches the hashtags a remote actor features, through {@link FeaturedTagFetch}. A job starts with the actor's
 * {@code featuredTags} collection, and can go on to its first page.
 *
 * @param actorId the actor's ID here
 * @param page    the collection's first page, once the collection has named one to fetch, or null
 * @param turn    the turn {@link TargetFetchTurns} gave it for the next fetch, or null if it hasn't waited for one
 */
record FeaturedTagFetchJob(String actorId, Instant queuedAt, String page, Instant turn) implements QueuedJob {

    static FeaturedTagFetchJob of(String actorId, Instant queuedAt) {
        return new FeaturedTagFetchJob(actorId, queuedAt, null, null);
    }

    FeaturedTagFetchJob withTurn(Instant turn) {
        return new FeaturedTagFetchJob(actorId, queuedAt, page, turn);
    }

    FeaturedTagFetchJob forPage(URI page) {
        return new FeaturedTagFetchJob(actorId, queuedAt, page.toString(), null);
    }
}
