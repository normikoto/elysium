package dev.biribiri.elysium.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * A sender's filtered mentions and quotes, waiting for the recipient to accept or dismiss them.
 *
 * @param updatedAt          when the newest of them arrived
 * @param notificationsCount how many there are, counting no further than {@link #MAX_COUNT}, as on Mastodon
 * @param lastStatusId       the status of the newest
 */
public record NotificationRequest(UUID id, UUID senderId, Instant createdAt, Instant updatedAt, long notificationsCount,
                                  UUID lastStatusId) {
    public static final int MAX_COUNT = 100;

    /**
     * How many requests are waiting, and how many notifications they hold, each counting no further than
     * {@link #MAX_COUNT}, as on Mastodon.
     */
    public record Summary(long requests, long notifications) {
    }
}
