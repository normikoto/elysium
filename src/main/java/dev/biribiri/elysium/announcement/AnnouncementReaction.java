package dev.biribiri.elysium.announcement;

import dev.biribiri.elysium.content.CustomEmoji;

/**
 * Everyone's reactions to an announcement with one emoji.
 *
 * @param name  the Unicode emoji, or the custom emoji's shortcode
 * @param emoji this server's custom emoji, or null for a Unicode one
 * @param me    whether the viewer is one of them
 */
public record AnnouncementReaction(String name, CustomEmoji emoji, long count, boolean me) {
}
