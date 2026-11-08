package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A status was boosted. Listeners must check that the boost is still current before projecting or sending it.
 *
 * @param boostId  the boost's own status ID
 * @param statusId the status it boosts
 * @param authorId that status's author
 */
public record StatusReblogged(UUID boostId, UUID actorId, UUID statusId, UUID authorId) {
}
