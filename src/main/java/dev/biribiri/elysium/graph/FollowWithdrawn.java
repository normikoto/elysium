package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A local account has stopped following, or withdrawn its request to follow, an actor on another server, which has to be
 * sent an {@code Undo}. The follow is gone by now, so this has what the {@code Undo} needs.
 *
 * @param activityUri the {@code id} of the {@code Follow} being undone
 */
public record FollowWithdrawn(UUID followerId, UUID followedId, String activityUri) {
}
