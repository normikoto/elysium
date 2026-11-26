package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local account stopped featuring a hashtag. The featured tag is gone by the time a listener runs, so this carries its
 * name, to send the {@code Remove} of what was added.
 *
 * @param name the tag's normalized name
 */
public record TagUnfeatured(UUID featuredTagId, UUID accountId, String name) {
}
