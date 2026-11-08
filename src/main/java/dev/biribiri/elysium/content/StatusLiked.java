package dev.biribiri.elysium.content;

import java.util.UUID;

/** Listeners must check that this like is still current before projecting or sending it. */
public record StatusLiked(UUID likeId, UUID actorId, UUID statusId, UUID authorId) {
}
