package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Sends a quote's {@code QuoteRequest} again if it's still unanswered, through {@link QuoteDeliveries}.
 *
 * @param statusId         the quoting status
 * @param authorId         its author
 * @param waitedForSending whether the request was still being sent when this last ran, so that it may only just have
 *                         arrived
 */
record QuoteRequestResendJob(UUID statusId, UUID authorId, Instant queuedAt, boolean waitedForSending)
        implements QueuedJob {

    QuoteRequestResendJob waitingForSending(boolean waiting) {
        return new QuoteRequestResendJob(statusId, authorId, queuedAt, waiting);
    }
}
