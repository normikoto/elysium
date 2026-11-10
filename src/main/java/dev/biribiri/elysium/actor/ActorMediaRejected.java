package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * An admin started rejecting a remote account's attachments, so the copies of them have to be removed.
 */
public record ActorMediaRejected(UUID actorId) {
}
