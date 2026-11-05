package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * A local actor was permanently deleted. Its row stays, so its statuses and follows are still there for a listener to remove.
 */
public record AccountDeleted(UUID actorId) {
}
