package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmojis;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This server's custom emoji named in profile text, by shortcode, for {@link dev.biribiri.elysium.text.EmojiHtml}. Only
 * local profiles are shown on these pages, so only local emoji are looked up.
 */
@Component
class LocalEmojiUrls {

    private final CustomEmojis emojis;
    private final InstanceProperties instance;

    LocalEmojiUrls(CustomEmojis emojis, InstanceProperties instance) {
        this.emojis = emojis;
        this.instance = instance;
    }

    /**
     * One lookup for all of the texts, such as every bio on a page.
     */
    Map<String, String> in(Collection<String> texts) {
        return emojis.findLocalIn(texts).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().url(instance).toString()));
    }
}
