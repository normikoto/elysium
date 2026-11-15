package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * Another server's custom emoji was stored, or its image changed, to be downloaded.
 */
public record RemoteEmojiAdded(UUID emojiId) {
}
