package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * Listeners must check that this reaction is still current before projecting or sending it.
 */
public record StatusReacted(UUID reactionId, UUID actorId, UUID statusId, UUID authorId) {
}
