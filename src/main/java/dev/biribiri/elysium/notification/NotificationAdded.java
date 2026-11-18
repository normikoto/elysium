package dev.biribiri.elysium.notification;

import java.util.UUID;

/**
 * A local account has a new notification. It can be dismissed, or its sender hidden, by the time a listener runs.
 */
public record NotificationAdded(UUID notificationId, UUID recipientId) {
}
