package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Follows, blocks, or mutes the accounts left in an import, a batch at a time.
 */
record RelationshipImportJob(UUID importId, Instant queuedAt) implements QueuedJob {
}
