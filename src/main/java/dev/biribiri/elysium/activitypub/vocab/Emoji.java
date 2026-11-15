package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmoji;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A {@code tag} naming a custom emoji that a status or a profile uses, as Mastodon sends one. Servers find
 * {@code :shortcode:} in the text and show the image in its place. {@code Emoji} is in Mastodon's namespace, so a
 * document that has one declares it in its {@code @context}.
 *
 * @param name the shortcode, with its colons
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Emoji(@JsonProperty("@context") List<?> context, URI id, String type, String name, Image icon)
        implements Tag {

    static final String CONTEXT_TERM = "Emoji";
    static final String CONTEXT_IRI = "toot:Emoji";

    /**
     * @param mediaType null for another server's image that isn't kept here, whose type isn't known
     */
    public record Image(String type, String mediaType, URI url) {
    }

    public static Emoji of(CustomEmoji emoji, InstanceProperties instance) {
        return new Emoji(
                null,
                emoji.uri(),
                "Emoji",
                ":" + emoji.shortcode() + ":",
                new Image("Image", emoji.imageName() == null ? null : "image/webp", emoji.url(instance)));
    }

    /**
     * A local emoji as it's served at its ID, as Mastodon serves its own.
     */
    public static Emoji document(CustomEmoji emoji, InstanceProperties instance) {
        var tag = of(emoji, instance);
        return new Emoji(List.of(ActivityStreams.CONTEXT, Map.of("toot", "http://joinmastodon.org/ns#",
                CONTEXT_TERM, CONTEXT_IRI)), tag.id(), tag.type(), tag.name(), tag.icon());
    }

    public static List<Emoji> of(Collection<CustomEmoji> emojis, InstanceProperties instance) {
        return emojis.stream().map(emoji -> of(emoji, instance)).toList();
    }
}
