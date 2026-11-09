package dev.biribiri.elysium.activitypub.vocab;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.text.Hashtags;

import java.net.URI;
import java.util.Collection;
import java.util.List;

/**
 * A {@code tag} naming a hashtag that a status or a bio uses. {@code Hashtag} isn't in the ActivityStreams vocabulary, so a
 * document that has one declares it in its {@code @context}, as Mastodon does.
 *
 * @param href this server's page of public statuses with the tag
 * @param name the normalized name, with its {@code #}
 */
public record Hashtag(String type, URI href, String name) implements Tag {

    static final String CONTEXT_TERM = "Hashtag";
    static final String CONTEXT_IRI = "as:Hashtag";

    /**
     * @param names normalized names
     */
    public static List<Hashtag> of(Collection<String> names, InstanceProperties instance) {
        return names.stream()
                .map(name -> new Hashtag("Hashtag", Hashtags.url(instance.baseUrl(), name), "#" + name))
                .toList();
    }
}
