package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Follows, blocks, or mutes the accounts left in an import, a batch at a time.
 */
record RelationshipImportJob(UUID importId, Instant queuedAt) implements QueuedJob {

    /**
     * The job as it's queued again, to carry on or to wait. Retries are timed from queuedAt, so a large import that's been
     * working for days would otherwise be given up on at its first failure, and its import would stay running, refusing
     * every other.
     */
    RelationshipImportJob requeued() {
        return new RelationshipImportJob(importId, Instant.now());
    }
}
