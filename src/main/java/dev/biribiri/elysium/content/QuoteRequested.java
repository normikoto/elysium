package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local status quotes a status on another server, whose author is to be asked for approval.
 */
public record QuoteRequested(UUID statusId, UUID authorId) {
}
