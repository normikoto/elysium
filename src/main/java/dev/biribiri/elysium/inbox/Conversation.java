package dev.biribiri.elysium.inbox;

import java.util.List;
import java.util.UUID;

public record Conversation(UUID id, boolean unread, List<UUID> accountIds, UUID lastStatusId) {
}
