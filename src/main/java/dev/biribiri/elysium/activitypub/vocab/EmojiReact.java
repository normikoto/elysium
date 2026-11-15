package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reaction with an emoji, as Pleroma sends it and FEP-c0e0 describes it, addressed only to the status's author, as a
 * {@link Like} is. A custom emoji is {@code :shortcode:} in {@code content}, with an {@link Emoji} in {@code tag}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmojiReact(@JsonProperty("@context") List<Object> context, String id, String type, URI actor,
                         URI object, String content, List<Emoji> tag, List<URI> to) {

    private static final List<Object> CONTEXT = List.of(ActivityStreams.CONTEXT, terms());

    private static Map<String, Object> terms() {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("toot", "http://joinmastodon.org/ns#");
        terms.put(Emoji.CONTEXT_TERM, Emoji.CONTEXT_IRI);
        terms.put("EmojiReact", "http://litepub.social/ns#EmojiReact");
        return terms;
    }

    /**
     * @param emoji the custom emoji, or null for a Unicode one
     */
    public static EmojiReact of(String id, URI actor, URI status, URI author, String content, Emoji emoji) {
        return new EmojiReact(CONTEXT, id, "EmojiReact", actor, status, content, emoji == null ? null : List.of(emoji),
                List.of(author));
    }

    public EmojiReact withoutContext() {
        return new EmojiReact(null, id, type, actor, object, content, tag, to);
    }
}
