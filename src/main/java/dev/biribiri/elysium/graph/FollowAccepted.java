package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * An account on another server accepted a local account's request to follow it. Only published when the follow was
 * waiting, so a repeated {@code Accept} doesn't publish it again.
 */
public record FollowAccepted(UUID followerId, UUID followedId) {
}
