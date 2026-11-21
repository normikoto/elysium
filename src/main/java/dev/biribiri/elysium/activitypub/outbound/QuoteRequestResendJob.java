package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Sends a quote's {@code QuoteRequest} again if it's still unanswered, through {@link QuoteDeliveries}.
 *
 * @param statusId         the quoting status
 * @param authorId         its author
 * @param waitedForSending whether the request was still being sent when this last ran, so that it may only just have
 *                         arrived
 * @param requestUri       the request to send again, which its author can have replaced by asking again, or null for a job
 *                         queued before they could
 */
record QuoteRequestResendJob(UUID statusId, UUID authorId, Instant queuedAt, boolean waitedForSending, String requestUri)
        implements QueuedJob {

    QuoteRequestResendJob waitingForSending(boolean waiting) {
        return new QuoteRequestResendJob(statusId, authorId, queuedAt, waiting, requestUri);
    }
}
