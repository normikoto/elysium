package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A follower of a local account, or a request to follow it, has been removed because the account blocked the follower or its
 * server. A remote follower's server has to be sent a {@code Reject}, so that it stops counting the follow.
 *
 * @param followId    the follow that was removed
 * @param activityUri the {@code Follow} it came in, or null if the follower is local
 */
public record FollowerRemoved(UUID followId, UUID followerId, UUID followedId, String activityUri) {
}
