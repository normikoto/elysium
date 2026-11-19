package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local author approved or rejected a quote of their status by hand.
 *
 * @param statusId the quoting status
 * @param accepted whether it was approved
 */
public record QuoteReviewed(UUID statusId, UUID authorId, UUID quotedAuthorId, boolean accepted) {
}
