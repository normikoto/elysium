package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Sends a quote's {@code QuoteRequest} again if it's still unanswered, through {@link QuoteDeliveries}.
 *
 * @param statusId the quoting status
 * @param authorId its author
 */
record QuoteRequestResendJob(UUID statusId, UUID authorId, Instant queuedAt) implements QueuedJob {
}
