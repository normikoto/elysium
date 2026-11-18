package dev.biribiri.elysium.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * @param reportId   the report an {@code admin.report} is about, or null
 * @param reactionId the reaction a {@code pleroma:emoji_reaction} is about, or null
 * @param targetId   the account that the {@code accountId} of a {@code move} moved to, or null
 */
public record Notification(UUID id, String type, Instant createdAt, UUID accountId, UUID statusId, String groupKey,
                           UUID reportId, UUID reactionId, UUID targetId) {

    /**
     * Pleroma's name for it, which clients that show reactions look for.
     */
    public static final String REACTION = "pleroma:emoji_reaction";
}
