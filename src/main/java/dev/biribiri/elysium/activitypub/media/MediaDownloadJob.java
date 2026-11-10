package dev.biribiri.elysium.activitypub.media;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Downloads one of another server's attachments, through {@link MediaDownloads}.
 */
record MediaDownloadJob(UUID attachmentId, Instant queuedAt) implements QueuedJob {
}
