package dev.biribiri.elysium.moderation;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Removes what's stored from a blocked server.
 */
record DomainBlockCleanupJob(UUID blockId, Instant queuedAt) implements QueuedJob {
}
