package dev.biribiri.elysium.actor;

import java.time.Instant;
import java.util.UUID;

/**
 * An account moved to another, which its followers here and, for a local account, its followers' servers have to be told
 * about.
 *
 * @param moveId   which move, for {@link AccountMigrations#find}
 * @param actorId  the account that moved
 * @param targetId the account it moved to
 * @param at       when, which tells this move apart from any other
 */
public record AccountMoved(UUID moveId, UUID actorId, UUID targetId, Instant at) {
}
