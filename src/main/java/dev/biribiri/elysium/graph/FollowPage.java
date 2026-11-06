package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A page of follows, newest first. The cursors are the IDs of follows from {@link FollowEntry#followId}.
 *
 * @param maxId   only follows older than this one
 * @param sinceId only follows newer than this one, newest first
 * @param minId   only follows newer than this one, starting from the oldest of them, but still listed newest first
 */
public record FollowPage(int limit, UUID maxId, UUID sinceId, UUID minId) {

    public static final int DEFAULT_LIMIT = 40;
    public static final int MAX_LIMIT = 80;

    public FollowPage {
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }

    public static FollowPage first(int limit) {
        return new FollowPage(limit, null, null, null);
    }
}
