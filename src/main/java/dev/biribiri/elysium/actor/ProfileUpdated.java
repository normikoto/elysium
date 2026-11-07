package dev.biribiri.elysium.actor;

import java.time.Instant;
import java.util.UUID;

/**
 * A local account changed its profile.
 *
 * @param at when, which tells this change apart from any other
 */
public record ProfileUpdated(UUID actorId, Instant at) {
}
