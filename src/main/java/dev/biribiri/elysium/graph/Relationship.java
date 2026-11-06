package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * How one account is related to another, from the first one's side.
 *
 * @param following  whether the first account follows the other
 * @param requested  whether the first account has asked to follow the other, which hasn't answered yet
 * @param followedBy whether the other account follows the first
 */
public record Relationship(UUID otherId, boolean following, boolean requested, boolean followedBy) {
}
