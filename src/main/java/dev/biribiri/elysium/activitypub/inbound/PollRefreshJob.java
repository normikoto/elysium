package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Fetches a remote poll's counts, through {@link PollRefreshes}.
 *
 * @param statusId the poll's status
 */
record PollRefreshJob(UUID statusId, Instant queuedAt) implements QueuedJob {
}
