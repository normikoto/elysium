package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;

/**
 * A custom emoji that another server's status or activity uses, as it's been read from there.
 *
 * @param shortcode a valid shortcode, without colons
 * @param uri       its ID, or null if none was given
 * @param url       its image
 * @param updated   when its server says it last changed, or null if it doesn't say
 */
public record RemoteEmoji(String shortcode, URI uri, URI url, Instant updated) {
}
