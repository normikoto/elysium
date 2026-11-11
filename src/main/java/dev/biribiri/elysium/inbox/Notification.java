package dev.biribiri.elysium.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * @param reportId the report an {@code admin.report} is about, or null
 */
public record Notification(UUID id, String type, Instant createdAt, UUID accountId, UUID statusId, String groupKey,
                           UUID reportId) {
}
