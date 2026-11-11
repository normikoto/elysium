package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local author withdrew their approval of a quote of their status.
 *
 * @param statusId the quoting status
 * @param stampId  the {@code QuoteAuthorization} that was withdrawn, or null if none was given
 */
public record QuoteRevoked(UUID statusId, UUID authorId, UUID quotedStatusId, UUID quotedAuthorId, UUID stampId) {
}
