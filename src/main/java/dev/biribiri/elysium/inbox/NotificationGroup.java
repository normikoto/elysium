package dev.biribiri.elysium.inbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationGroup(
        String groupKey, long notificationsCount, String type, UUID mostRecentNotificationId,
        UUID pageMinId, UUID pageMaxId, Instant latestPageNotificationAt, List<UUID> sampleAccountIds, UUID statusId, UUID paginationId) {
}
