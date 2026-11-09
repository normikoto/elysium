package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * Someone asked to follow a local account that approves its followers by hand, and the request is waiting for it.
 */
public record FollowRequestReceived(UUID followId, UUID followerId, UUID followedId) {
}
