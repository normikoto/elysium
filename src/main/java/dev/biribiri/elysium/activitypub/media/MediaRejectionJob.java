package dev.biribiri.elysium.activitypub.media;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Applies an admin's rejection of media to what's stored, through {@link MediaRejections}. One of the IDs is set.
 *
 * @param blockId the domain block that rejects a server's media
 * @param actorId the remote account whose media is rejected
 */
record MediaRejectionJob(UUID blockId, UUID actorId, Instant queuedAt) implements QueuedJob {
}
