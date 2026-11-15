package dev.biribiri.elysium.content;

import java.net.URI;
import java.util.UUID;

/**
 * The reaction being withdrawn, kept after its row has been removed, so that its {@code Undo} can say what it undoes.
 *
 * @param name    the Unicode emoji or shortcode, which is never a status's text
 * @param emojiId the custom emoji, or null for a Unicode one
 */
public record StatusUnreacted(UUID actorId, UUID statusId, UUID authorId, URI statusUri, String activityUri, String name,
                              UUID emojiId) {
}
