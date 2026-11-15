package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.UUID;

/**
 * One actor's reaction to a status with one emoji.
 *
 * @param name        the Unicode emoji, or the custom emoji's shortcode
 * @param emojiId     the custom emoji, or null for a Unicode one
 * @param activityUri the {@code EmojiReact}, or the {@code Like} with an emoji, that it was sent as
 * @param activityAt  when that activity first arrived, or when the reaction was made here
 */
public record StatusReaction(UUID id, UUID actorId, UUID statusId, String name, UUID emojiId, String activityUri,
                             Instant activityAt) {
}
