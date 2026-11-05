package dev.biribiri.elysium.actor;

import java.time.Instant;
import java.util.UUID;

/**
 * A local or remote actor's suspension was ended.
 *
 * @param at when, which tells this from any other time the actor's suspension ended
 */
public record AccountUnsuspended(UUID actorId, Instant at) {
}
