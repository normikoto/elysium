package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local author withdrew their approval of a quote of their status.
 *
 * @param statusId the quoting status
 * @param stampId  the {@code QuoteAuthorization} that was withdrawn, or null if none was given
 * @param localOnly whether the quoting status is local-only, so nothing elsewhere knows of the quote. False for an event
 *                  stored before this was added, when there were none
 */
public record QuoteRevoked(UUID statusId, UUID authorId, UUID quotedAuthorId, UUID stampId, boolean localOnly) {
}
