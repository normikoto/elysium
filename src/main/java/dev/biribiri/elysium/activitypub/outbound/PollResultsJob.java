package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Sends a local poll's counts, through {@link PollDeliveries}.
 *
 * @param statusId the poll's status
 */
record PollResultsJob(UUID statusId, UUID authorId, Instant queuedAt) implements QueuedJob {
}
