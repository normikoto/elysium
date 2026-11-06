package dev.biribiri.elysium.graph;

import java.time.Instant;
import java.util.UUID;

/**
 * A local account has asked to follow an actor on another server, which has to be sent a {@code Follow}. Published again
 * when the account asks again while the request is still waiting, since the other server may have lost it.
 *
 * @param activityUri the {@code id} of the {@code Follow}, which an {@code Accept} or {@code Reject} names
 * @param requestedAt when the account asked, which tells a new request apart from this event being handled again
 */
public record FollowRequested(UUID followerId, UUID followedId, String activityUri, Instant requestedAt) {
}
