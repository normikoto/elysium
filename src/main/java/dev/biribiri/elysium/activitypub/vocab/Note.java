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
import java.util.stream.Stream;

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

    static final List<Object> CONTEXT = List.of(
            ActivityStreams.CONTEXT,
            Map.of("sensitive", "as:sensitive"));

    private static final List<String> PUBLIC = List.of(ActivityStreams.PUBLIC);

    /**
     * @param inReplyTo    the parent status, or null if there's none to show
     * @param parentAuthor who wrote the parent status, or null if there's none to show
     * @throws IllegalArgumentException if this status is direct, because there's no way yet to address it to anyone
     */
    public static Note from(Status status, Actor author, URI inReplyTo, URI parentAuthor) {
        var followers = List.of(Person.followersOf(author).toString());
        var parent = parentAuthor == null || parentAuthor.equals(author.uri())
                ? List.<String>of()
                : List.of(parentAuthor.toString());
        // Other servers tell these apart by whether the public collection is in "to", in "cc", or in neither.
        var audience = switch (status.visibility()) {
            case PUBLIC -> new Audience(PUBLIC, concat(followers, parent));
            case UNLISTED -> new Audience(followers, concat(PUBLIC, parent));
            case PRIVATE -> new Audience(followers, parent);
            case DIRECT -> throw new IllegalArgumentException("The status is direct");
        };

        return new Note(
                CONTEXT,
                status.uri(),
                "Note",
                author.uri(),
                // Browsers get the status's page from the same URL as this document.
                status.uri(),
                DateTimeFormatter.ISO_INSTANT.format(status.publishedAt().truncatedTo(ChronoUnit.SECONDS)),
                audience.to(),
                audience.cc(),
                status.content(),
                status.sensitive(),
                status.spoilerText().isEmpty() ? null : status.spoilerText(),
                inReplyTo,
                status.language() == null ? null : Map.of(status.language(), status.content()));
    }

    /**
     * @return this object as part of an activity, which has the {@code @context} for both
     */
    public Note withoutContext() {
        return new Note(null, id, type, attributedTo, url, published, to, cc, content, sensitive, summary, inReplyTo, contentMap);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    private record Audience(List<String> to, List<String> cc) {
    }
}
