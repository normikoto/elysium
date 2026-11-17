package dev.biribiri.elysium.actor;

import java.time.Instant;
import java.util.UUID;

/**
 * A move that was acted on.
 *
 * @param actorId  the account that moved
 * @param targetId the account it moved to
 */
public record AccountMove(UUID id, UUID actorId, UUID targetId, Instant createdAt) {
}
