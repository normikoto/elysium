package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.UUID;

/**
 * @param name          the tag's normalized name
 * @param statusesCount how many of the account's public and unlisted statuses have the tag
 * @param lastStatusAt  when the newest of them was published, or null if there are none
 */
public record FeaturedTag(UUID id, String name, long statusesCount, Instant lastStatusAt) {
}
