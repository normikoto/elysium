package dev.biribiri.elysium.moderation;

import java.time.Instant;
import java.util.UUID;

/**
 * One of this server's rules, which people agree to when they sign up.
 *
 * @param text     plain text
 * @param hint     plain text that explains it, empty for none
 * @param position where it goes among the others, lowest first
 */
public record Rule(UUID id, String text, String hint, int position, Instant createdAt, Instant updatedAt) {
}
