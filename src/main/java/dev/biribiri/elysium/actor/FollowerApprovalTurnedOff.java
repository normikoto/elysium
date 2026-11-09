package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * A local account stopped approving its followers by hand, so the requests still waiting for it should be approved, as
 * Mastodon does.
 */
public record FollowerApprovalTurnedOff(UUID actorId) {
}
