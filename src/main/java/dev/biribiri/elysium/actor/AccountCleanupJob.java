package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Removes what shouldn't be kept for a suspended remote actor or a deleted one.
 */
record AccountCleanupJob(UUID actorId, Instant queuedAt) implements QueuedJob {
}
