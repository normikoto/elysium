package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Removes what shouldn't be kept for a suspended remote actor or a deleted local one.
 */
record AccountCleanupJob(UUID actorId, Instant queuedAt) implements QueuedJob {
}
