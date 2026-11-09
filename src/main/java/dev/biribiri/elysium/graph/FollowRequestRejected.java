package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A local account refused a request to follow it, which has been removed.
 *
 * @param activityUri the {@code Follow} the request came in, to answer with a {@code Reject}, or null if the follower is
 *                    local
 */
public record FollowRequestRejected(UUID followerId, UUID followedId, String activityUri) {
}
