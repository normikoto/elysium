package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * An admin stopped rejecting a remote account's attachments.
 */
public record ActorMediaAllowed(UUID actorId) {
}
