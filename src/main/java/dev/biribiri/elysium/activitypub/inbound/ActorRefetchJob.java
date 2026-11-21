package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;

/**
 * Fetches an actor's document again a while after its {@code Update}, through {@link ActorRefetch}.
 */
record ActorRefetchJob(String actorUri, Instant queuedAt) implements QueuedJob {
}
