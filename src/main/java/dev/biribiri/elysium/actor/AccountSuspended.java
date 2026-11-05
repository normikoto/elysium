package dev.biribiri.elysium.actor;

import java.time.Instant;
import java.util.UUID;

/**
 * A local or remote actor was suspended.
 *
 * @param at when, which tells this suspension of an actor apart from any other
 */
public record AccountSuspended(UUID actorId, Instant at) {
}
