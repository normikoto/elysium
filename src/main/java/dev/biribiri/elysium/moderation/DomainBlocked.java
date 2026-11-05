package dev.biribiri.elysium.moderation;

import java.util.UUID;

/**
 * A server has been blocked, so what's stored from it has to be removed.
 */
public record DomainBlocked(UUID blockId) {
}
