package dev.biribiri.elysium.graph;

import java.time.Instant;
import java.util.UUID;

/**
 * How one account is related to another, from the first one's side.
 *
 * @param following           whether the first account follows the other
 * @param requested           whether the first account has asked to follow the other, which hasn't answered yet
 * @param followedBy          whether the other account follows the first
 * @param requestedBy         whether the other account has asked to follow the first, which hasn't answered yet
 * @param blocking            whether the first account blocks the other
 * @param blockedBy           whether the other account blocks the first
 * @param muting              whether the first account has muted the other, and the mute hasn't expired
 * @param mutingNotifications whether that mute also hides the other's notifications
 * @param muteExpiresAt       when that mute ends, or null if it doesn't, or there's none
 * @param domainBlocking      whether the first account has blocked the other's server
 */
public record Relationship(
        UUID otherId,
        boolean following,
        boolean requested,
        boolean followedBy,
        boolean requestedBy,
        boolean blocking,
        boolean blockedBy,
        boolean muting,
        boolean mutingNotifications,
        Instant muteExpiresAt,
        boolean domainBlocking) {
}
