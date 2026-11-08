package dev.biribiri.elysium.inbox;

import java.util.UUID;

/** Results are newest first; minId selects the closest newer results and takes precedence over sinceId. */
public record InboxPage(int limit, UUID maxId, UUID sinceId, UUID minId) {
    public static InboxPage notifications(int limit, UUID maxId, UUID sinceId, UUID minId) {
        return new InboxPage(Math.clamp(limit, 1, 80), maxId, sinceId, minId);
    }

    public static InboxPage conversations(int limit, UUID maxId, UUID sinceId, UUID minId) {
        return new InboxPage(Math.clamp(limit, 1, 40), maxId, sinceId, minId);
    }
}
