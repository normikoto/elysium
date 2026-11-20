package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local author's edit stopped one of their statuses quoting a local status, or made it quote another, so what its
 * quoted author was told of the quote no longer holds.
 *
 * @param statusId the status that quoted it
 */
public record QuoteRemoved(UUID statusId, UUID authorId, UUID quotedAuthorId) {
}
