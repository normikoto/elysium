package dev.biribiri.elysium.graph;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How one account is related to another, from the first one's side.
 *
 * @param following           whether the first account follows the other
 * @param requested           whether the first account has asked to follow the other, which hasn't answered yet
 * @param showingReblogs      whether that follow or request lets the other's boosts into the first's feeds
 * @param notifying           whether that follow or request notifies the first of the other's new statuses
 * @param languages           the languages that follow or request limits the other's statuses to, or empty for every one
 * @param followedBy          whether the other account follows the first
 * @param requestedBy         whether the other account has asked to follow the first, which hasn't answered yet
 * @param blocking            whether the first account blocks the other
 * @param blockedBy           whether the other account blocks the first
 * @param muting              whether the first account has muted the other, and the mute hasn't expired
 * @param mutingNotifications whether that mute also hides the other's notifications
 * @param muteExpiresAt       when that mute ends, or null if it doesn't, or there's none
 * @param domainBlocking      whether the first account has blocked the other's server
 * @param endorsed            whether the first account endorses the other, which it follows
 * @param memo                the first account's private memo about the other, or empty if it has none
 */
public record Relationship(
        UUID otherId,
        boolean following,
        boolean requested,
        boolean showingReblogs,
        boolean notifying,
        List<String> languages,
        boolean followedBy,
        boolean requestedBy,
        boolean blocking,
        boolean blockedBy,
        boolean muting,
        boolean mutingNotifications,
        Instant muteExpiresAt,
        boolean domainBlocking,
        boolean endorsed,
        String memo) {
}
