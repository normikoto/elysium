package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A quote of a local status waits for its author to approve or reject it by hand.
 *
 * @param statusId the quoting status
 */
public record QuoteReviewRequested(UUID statusId, UUID authorId, UUID quotedAuthorId) {
}
