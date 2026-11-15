package dev.biribiri.elysium.activitypub.media;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Downloads another server's custom emoji, through {@link MediaDownloads}.
 */
record EmojiDownloadJob(UUID emojiId, Instant queuedAt) implements QueuedJob {
}
