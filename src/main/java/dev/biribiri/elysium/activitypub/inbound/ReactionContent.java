package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.RemoteEmoji;
import dev.biribiri.elysium.text.Shortcodes;
import dev.biribiri.elysium.text.UnicodeEmoji;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Reads the emoji of a reaction, as FEP-c0e0 describes them: Pleroma's {@code EmojiReact}, and Misskey's {@code Like},
 * both of which have it in {@code content}, and Misskey's also in {@code _misskey_reaction}. A custom emoji is
 * {@code :shortcode:}, with an {@code Emoji} in {@code tag} for its image.
 *
 * @param name   the Unicode emoji, or the custom emoji's shortcode
 * @param emoji  the custom emoji, or null for a Unicode one
 * @param domain the server a custom emoji is from, when it names one, or null for the actor's own
 */
record ReactionContent(String name, RemoteEmoji emoji, String domain) {

    /**
     * @param activity the activity's JSON
     * @return empty if it has no emoji, or names a custom one that it gives no image for
     */
    static Optional<ReactionContent> read(JsonNode activity) {
        var content = Optional.ofNullable(ActivityJson.text(activity.path("content")))
                .or(() -> Optional.ofNullable(ActivityJson.text(activity.path("_misskey_reaction"))))
                .map(String::strip)
                .orElse(null);
        if (content == null) {
            return Optional.empty();
        }
        if (UnicodeEmoji.isOne(content)) {
            return Optional.of(new ReactionContent(content, null, null));
        }
        if (content.length() < 3 || !content.startsWith(":") || !content.endsWith(":")) {
            return Optional.empty();
        }
        // Misskey names an emoji from another server as :shortcode@host:, and its own as :shortcode@.: inside itself, and
        // its tag by the bare shortcode.
        var bare = content.substring(1, content.length() - 1);
        var at = bare.indexOf('@');
        var shortcode = Shortcodes.parse(at < 0 ? bare : bare.substring(0, at));
        var host = at < 0 ? null : bare.substring(at + 1);
        var domain = host == null || host.isEmpty() || host.equals(".") ? null : host;
        return shortcode.flatMap(name -> RemoteEmojis.read(activity.path("tag")).stream()
                .filter(emoji -> emoji.shortcode().equals(name))
                .findFirst()
                .map(emoji -> new ReactionContent(name, emoji, domain)));
    }
}
