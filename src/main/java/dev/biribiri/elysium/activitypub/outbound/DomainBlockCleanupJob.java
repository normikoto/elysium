package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Removes what's stored from a blocked server.
 */
record DomainBlockCleanupJob(UUID blockId, Instant queuedAt) implements QueuedJob {
}
