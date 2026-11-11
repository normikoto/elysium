package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A quote of a local status was approved, or needed no approval.
 *
 * @param statusId the quoting status
 */
public record QuoteAccepted(UUID statusId, UUID authorId, UUID quotedStatusId, UUID quotedAuthorId) {
}
