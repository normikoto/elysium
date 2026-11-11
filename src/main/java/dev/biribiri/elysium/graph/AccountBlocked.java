package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * An account has blocked another: a local account, which may have to send a {@code Block}, or a remote actor blocking a local
 * account. Follows between them have been removed by now, with their own events.
 *
 * @param activityUri the {@code id} of the {@code Block}
 */
public record AccountBlocked(UUID blockerId, UUID blockedId, String activityUri) {
}
