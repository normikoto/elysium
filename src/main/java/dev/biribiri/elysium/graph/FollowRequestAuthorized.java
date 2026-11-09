package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A local account approved a request to follow it.
 *
 * @param activityUri the {@code Follow} the request came in, to answer with an {@code Accept}, or null if the follower is
 *                    local
 */
public record FollowRequestAuthorized(UUID followId, UUID followerId, UUID followedId, String activityUri) {
}
