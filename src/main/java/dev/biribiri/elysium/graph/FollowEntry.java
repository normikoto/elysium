package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * One accepted follow in a list of followers or of accounts followed.
 *
 * @param followId what to page from, with {@link FollowPage}
 * @param actorId  the other account: the follower in a list of followers, and the account followed in a list of following
 */
public record FollowEntry(UUID followId, UUID actorId) {
}
