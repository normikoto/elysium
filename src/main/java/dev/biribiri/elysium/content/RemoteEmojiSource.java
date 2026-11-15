package dev.biribiri.elysium.content;

import java.net.URI;
import java.util.UUID;

/**
 * Where to download another server's custom emoji from.
 *
 * @param domain the server it's from, which decides whose download budget it counts against
 */
public record RemoteEmojiSource(UUID emojiId, URI url, String domain) {
}
