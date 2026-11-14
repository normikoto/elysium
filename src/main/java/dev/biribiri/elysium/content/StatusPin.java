package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.UUID;

/**
 * A status its author pinned to their profile.
 *
 * @param activityUri the {@code Add}'s ID for a local pin, or null for a remote one, since other servers' {@code Add}s
 *                    don't have one
 */
public record StatusPin(UUID id, UUID actorId, UUID statusId, Instant pinnedAt, String activityUri) {
}
