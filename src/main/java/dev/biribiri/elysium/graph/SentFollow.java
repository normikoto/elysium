package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A local account's follow of an account on another server, or its request to, which that server was sent a {@code Follow}
 * for.
 *
 * @param followedId  the remote account
 * @param activityUri the {@code Follow} sent for it
 */
public record SentFollow(UUID followedId, String activityUri) {
}
