package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A local account's follow moved from one account to another, because the first moved to the second.
 *
 * @param moveId which move, which tells this apart from any other move of the same follow
 */
public record FollowMoved(UUID moveId, UUID followerId, UUID fromId, UUID toId) {
}
