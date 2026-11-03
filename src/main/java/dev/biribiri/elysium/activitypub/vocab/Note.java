package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Status;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * An ActivityPub object for a status.
 *
 * @param summary    the content warning as plain text, or null for none. ActivityStreams allows HTML here, but Mastodon
 *                   sends and reads this as plain text, so escaping it would show the escapes in its content warnings.
 * @param inReplyTo  the parent status, or null if this isn't a reply or the parent can't be shown
 * @param contentMap the content under its language, or null if no language is set
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Note(
        @JsonProperty("@context") List<Object> context,
        URI id,
        String type,
        URI attributedTo,
        URI url,
        String published,
        List<String> to,
        List<String> cc,
        String content,
        boolean sensitive,
        String summary,
        URI inReplyTo,
        Map<String, String> contentMap
) {

    private static final List<Object> CONTEXT = List.of(
            "https://www.w3.org/ns/activitystreams",
            Map.of("sensitive", "as:sensitive"));

    private static final List<String> PUBLIC = List.of("https://www.w3.org/ns/activitystreams#Public");

    /**
     * @param inReplyTo the parent status, or null if there's none to show
     * @throws IllegalArgumentException if this status can't be shown to everyone, because there's no way yet to tell who
     *                                  is asking for it or to address it to them
     */
    public static Note from(Status status, Actor author, URI inReplyTo) {
        // Actors have no followers collection to address yet. Other servers tell public and unlisted statuses apart by
        // whether the public collection is in "to" or "cc".
        var to = switch (status.visibility()) {
            case PUBLIC -> PUBLIC;
            case UNLISTED -> List.<String>of();
            case PRIVATE, DIRECT -> throw new IllegalArgumentException("The status isn't public or unlisted");
        };
        var cc = to.isEmpty() ? PUBLIC : List.<String>of();

        return new Note(
                CONTEXT,
                status.uri(),
                "Note",
                author.uri(),
                // Browsers get the status's page from the same URL as this document.
                status.uri(),
                DateTimeFormatter.ISO_INSTANT.format(status.publishedAt().truncatedTo(ChronoUnit.SECONDS)),
                to,
                cc,
                status.content(),
                status.sensitive(),
                status.spoilerText().isEmpty() ? null : status.spoilerText(),
                inReplyTo,
                status.language() == null ? null : Map.of(status.language(), status.content()));
    }
}
