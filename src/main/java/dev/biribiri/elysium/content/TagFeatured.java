package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local account featured a hashtag on its profile. Listeners must check that it's still featured before sending it.
 */
public record TagFeatured(UUID featuredTagId, UUID accountId) {
}
