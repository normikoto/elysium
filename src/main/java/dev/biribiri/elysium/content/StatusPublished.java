package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A new status was saved. It can be deleted again by the time a listener runs, so listeners have to look it up.
 */
public record StatusPublished(UUID statusId, UUID authorId) {
}
