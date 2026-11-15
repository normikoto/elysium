package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * Everyone's reactions to a status with one emoji.
 *
 * @param name       the Unicode emoji, or a custom emoji's shortcode, with {@code @} and its domain for another server's
 * @param emoji      the custom emoji, or null for a Unicode one
 * @param me         whether the viewer is one of them
 * @param accountIds the most recent of them, when they were asked for, and otherwise none
 */
public record ReactionSummary(String name, CustomEmoji emoji, long count, boolean me, List<UUID> accountIds) {

    public ReactionSummary {
        accountIds = List.copyOf(accountIds);
    }
}
