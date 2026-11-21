package dev.biribiri.elysium.activitypub.media;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Downloads the avatar or header of an account on another server, through {@link MediaDownloads}.
 *
 * @param header whether it's the header rather than the avatar
 */
record ProfileImageDownloadJob(UUID actorId, boolean header, Instant queuedAt) implements QueuedJob {
}
