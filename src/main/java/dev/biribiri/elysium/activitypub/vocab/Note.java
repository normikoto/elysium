package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.Visibility;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An ActivityPub object for a status.
 *
 * @param updated    when it was last edited, or null if it never was, which tells a server that gets two {@code Update}s
 *                   which is newer
 * @param summary    the content warning as plain text, or null for none. ActivityStreams allows HTML here, but Mastodon
 *                   sends and reads this as plain text, so escaping it would show the escapes in its content warnings.
 * @param inReplyTo  the parent status, or null if this isn't a reply or the parent can't be shown
 * @param contentMap the content under its language, or null if no language is set
 * @param tag        the accounts it mentions, or null if it mentions none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Note(
        @JsonProperty("@context") List<Object> context,
        URI id,
        String type,
        URI attributedTo,
        URI url,
        String published,
        String updated,
        List<String> to,
        List<String> cc,
        String content,
        boolean sensitive,
        String summary,
        URI inReplyTo,
        Map<String, String> contentMap,
        List<Mention> tag,
        URI likes,
        URI shares
) {

    static final List<Object> CONTEXT = List.of(
            ActivityStreams.CONTEXT,
            Map.of("sensitive", "as:sensitive"));

    /**
     * A direct status is only addressed to the accounts it mentions, so its parent's author has to be mentioned to get it,
     * as on Mastodon.
     *
     * @param inReplyTo    the parent status, or null if there's none to show
     * @param parentAuthor who wrote the parent status, or null if there's none to show
     * @param mentions     the accounts the status mentions
     */
    public static Note from(Status status, Actor author, URI inReplyTo, URI parentAuthor, List<Mention> mentions) {
        var others = new ArrayList<String>();
        mentions.forEach(mention -> others.add(mention.href().toString()));
        if (parentAuthor != null && !parentAuthor.equals(author.uri()) && status.visibility() != Visibility.DIRECT) {
            others.add(parentAuthor.toString());
        }
        var audience = Audience.of(status.visibility(), author, others);

        return new Note(
                CONTEXT,
                status.uri(),
                "Note",
                author.uri(),
                // Browsers get the status's page from the same URL as this document.
                status.uri(),
                ActivityStreams.time(status.publishedAt()),
                status.editedAt() == null ? null : ActivityStreams.time(status.editedAt()),
                audience.to(),
                audience.cc(),
                status.content(),
                status.sensitive(),
                status.spoilerText().isEmpty() ? null : status.spoilerText(),
                inReplyTo,
                status.language() == null ? null : Map.of(status.language(), status.content()),
                mentions.isEmpty() ? null : List.copyOf(mentions),
                URI.create(status.uri() + "/likes"),
                URI.create(status.uri() + "/shares"));
    }

    /**
     * @return this object as part of an activity, which has the {@code @context} for both
     */
    public Note withoutContext() {
        return new Note(
                null, id, type, attributedTo, url, published, updated, to, cc, content, sensitive, summary, inReplyTo, contentMap, tag, likes, shares);
    }
}
