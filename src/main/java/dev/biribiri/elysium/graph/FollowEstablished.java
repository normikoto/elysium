package dev.biribiri.elysium.graph;

import java.util.UUID;

/** A new accepted relationship, identified by the stored follow rather than a repeatable federation activity. */
public record FollowEstablished(UUID followId, UUID followerId, UUID followedId) {
}
