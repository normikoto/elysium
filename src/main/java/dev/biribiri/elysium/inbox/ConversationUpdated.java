package dev.biribiri.elysium.inbox;

import java.util.UUID;

/**
 * A status was added to one of a local account's conversations.
 */
public record ConversationUpdated(UUID conversationId, UUID ownerId) {
}
