package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.text.Shortcodes;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collection;
import java.util.List;

/**
 * Mastodon's CustomEmoji entity.
 */
@Schema(name = "CustomEmoji", description = """
        Mastodon's CustomEmoji entity. The images are WebP, and `static_url` is the first frame of an animated one. An \
        emoji from another server whose image isn't kept here links to that server's copy, as its attachments do.""")
record CustomEmojiResponse(
        String shortcode,
        String url,
        @JsonProperty("static_url") String staticUrl,
        @JsonProperty("visible_in_picker") boolean visibleInPicker,
        @Schema(nullable = true) String category
) {

    static CustomEmojiResponse of(CustomEmoji emoji, InstanceProperties instance) {
        return new CustomEmojiResponse(
                emoji.shortcode(),
                emoji.url(instance).toString(),
                emoji.staticUrl(instance).toString(),
                emoji.visibleInPicker(),
                emoji.category());
    }

    static List<CustomEmojiResponse> of(Collection<CustomEmoji> emojis, InstanceProperties instance) {
        return emojis.stream().map(emoji -> of(emoji, instance)).toList();
    }

    /**
     * Those that some texts name, as Mastodon gives a poll the emoji of its options.
     */
    static List<CustomEmojiResponse> namedIn(List<CustomEmojiResponse> emojis, Collection<String> texts) {
        if (emojis.isEmpty()) {
            return List.of();
        }
        var names = Shortcodes.names(texts);
        return emojis.stream().filter(emoji -> names.contains(emoji.shortcode())).toList();
    }
}
