package dev.biribiri.elysium.notification;

import java.util.UUID;

/** Results are newest first; minId selects the closest newer results and takes precedence over sinceId. */
public record NotificationPage(int limit, UUID maxId, UUID sinceId, UUID minId) {
    public static NotificationPage notifications(int limit, UUID maxId, UUID sinceId, UUID minId) {
        return new NotificationPage(Math.clamp(limit, 1, 80), maxId, sinceId, minId);
    }

    public static NotificationPage conversations(int limit, UUID maxId, UUID sinceId, UUID minId) {
        return new NotificationPage(Math.clamp(limit, 1, 40), maxId, sinceId, minId);
    }
}
