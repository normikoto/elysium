package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A local account has unblocked another. The block is gone by now, so this has what an {@code Undo} needs.
 *
 * @param activityUri the {@code id} of the {@code Block} being undone
 */
public record AccountUnblocked(UUID blockerId, UUID blockedId, String activityUri) {
}
