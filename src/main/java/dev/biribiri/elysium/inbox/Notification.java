package dev.biribiri.elysium.inbox;

import java.time.Instant;
import java.util.UUID;

public record Notification(UUID id, String type, Instant createdAt, UUID accountId, UUID statusId, String groupKey) {
}
