package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * A local account started approving requests from accounts it follows straight away, so those still waiting for it
 * should be approved.
 */
public record FollowedApprovalTurnedOn(UUID actorId) {
}
