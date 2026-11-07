package dev.biribiri.elysium.activitypub.links;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Checks the links in a local account's profile fields.
 */
record ProfileLinksJob(UUID actorId, Instant queuedAt) implements QueuedJob {
}
