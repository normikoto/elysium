package dev.biribiri.elysium.notification;

import java.util.List;
import java.util.UUID;

public record Conversation(UUID id, boolean unread, List<UUID> accountIds, UUID lastStatusId) {
}
