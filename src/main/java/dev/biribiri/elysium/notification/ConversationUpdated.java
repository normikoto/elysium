package dev.biribiri.elysium.notification;

import java.util.UUID;

/**
 * A status was added to one of a local account's conversations.
 */
public record ConversationUpdated(UUID conversationId, UUID ownerId) {
}
