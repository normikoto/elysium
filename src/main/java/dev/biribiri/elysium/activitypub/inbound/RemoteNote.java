package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.StatusService;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@code Note} from another server, read from its JSON. Nothing here has been checked, and {@code content} and
 * {@code summary} are HTML that hasn't been sanitized.
 *
 * @param attributedTo the IDs in its {@code attributedTo}
 * @param url          the page that shows it, or null if it doesn't give an HTTP or HTTPS one
 * @param content      its {@code content}, or the one in {@code contentMap}, or null if it has none
 * @param summary      its {@code summary}, or the one in {@code summaryMap}, or null if it has none
 * @param language     a key of {@code contentMap}, or null if it has none
 * @param inReplyTo    the ID of its {@code inReplyTo}, or null if it isn't a reply
 * @param published    its {@code published}, or null if that's missing or isn't a date
 * @param updated      its {@code updated}, when it was last edited, or null if that's missing or isn't a date
 * @param mentions     the {@code href} of each {@code Mention} in its {@code tag}, up to {@value #MAX_MENTIONS}
 * @param hashtags     the {@code name} of each {@code Hashtag} in its {@code tag}, which {@link StatusService} checks
 */
record RemoteNote(
        URI id,
        List<String> attributedTo,
        URI url,
        String content,
        String summary,
        String language,
        boolean sensitive,
        URI inReplyTo,
        Instant published,
        Instant updated,
        Addressing addressing,
        List<URI> mentions,
        List<String> hashtags) {

    /**
     * Each mention is looked up, so a note can't make this server look up any number of them. Mastodon has no limit, but
     * a note mentioning more accounts than this would be spam.
     */
    static final int MAX_MENTIONS = 100;

    /**
     * How far ahead of this server's clock an {@code updated} can be, which is what signatures allow.
     */
    private static final Duration CLOCK_SKEW = Duration.ofHours(1);

    RemoteNote {
        attributedTo = List.copyOf(attributedTo);
        mentions = List.copyOf(mentions);
        hashtags = List.copyOf(hashtags);
    }

    /**
     * When the note was edited, going by its {@code updated}, or null if it doesn't say, or what it says can't be an edit.
     * Some servers send an {@code updated} equal to {@code published} for notes that were never edited. One in the future
     * would keep every later edit from being applied, since edits are ordered by it.
     *
     * @param published when it was published, or null if that isn't known
     */
    Instant editedAt(Instant published, Instant now) {
        if (updated == null || updated.isAfter(now.plus(CLOCK_SKEW))) {
            return null;
        }
        return published == null || updated.isAfter(published) ? updated : null;
    }

    /**
     * @param node an object, embedded or fetched
     * @return the note, or empty if this isn't a {@code Note} with an ID
     */
    static Optional<RemoteNote> read(JsonNode node) {
        if (!node.isObject() || !ActivityJson.types(node.path("type")).contains("Note")) {
            return Optional.empty();
        }
        var id = Optional.ofNullable(ActivityJson.text(node.path("id"))).flatMap(ActivityObject::parseUri);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RemoteNote(
                id.get(),
                ActivityJson.ids(node.path("attributedTo")),
                url(node.path("url")),
                ActivityJson.naturalText(node, "content"),
                ActivityJson.naturalText(node, "summary"),
                language(node.path("contentMap")),
                ActivityJson.flag(node.path("sensitive")),
                Optional.ofNullable(ActivityJson.idOf(node.path("inReplyTo")))
                        .flatMap(ActivityObject::parseUri)
                        .orElse(null),
                timestamp(node.path("published")),
                timestamp(node.path("updated")),
                Addressing.of(node),
                mentions(node.path("tag")),
                hashtags(node.path("tag"))));
    }

    /**
     * Only the {@code tag} array decides a note's hashtags, as on Mastodon, not links in its content.
     */
    private static List<String> hashtags(JsonNode tag) {
        return ActivityJson.tags(tag).stream()
                .filter(candidate -> "Hashtag".equals(candidate.type()) && candidate.name() != null)
                .map(ActivityJson.Tag::name)
                .toList();
    }

    private static List<URI> mentions(JsonNode tag) {
        return ActivityJson.tags(tag).stream()
                .filter(candidate -> "Mention".equals(candidate.type()) && candidate.href() != null)
                .flatMap(candidate -> ActivityObject.parseUri(candidate.href()).stream())
                .distinct()
                .limit(MAX_MENTIONS)
                .toList();
    }

    /**
     * {@code url} can be a string, a {@code Link}, or an array of them. Mastodon sends a string, and others send a
     * {@code Link} for each format, so a {@code Link} only counts if it's HTML or doesn't say what it is.
     */
    private static URI url(JsonNode node) {
        for (var candidate : node.isArray() ? node.valueStream().toList() : List.of(node)) {
            String href;
            if (candidate.isObject()) {
                var mediaType = ActivityJson.text(candidate.path("mediaType"));
                href = mediaType == null || mediaType.equals("text/html")
                        ? ActivityJson.text(candidate.path("href"))
                        : null;
            } else {
                href = ActivityJson.text(candidate);
            }
            var uri = Optional.ofNullable(href)
                    .flatMap(ActivityObject::parseUri)
                    .filter(RemoteNote::isWebPage)
                    .filter(parsed -> InboundActivity.isStorableId(parsed.toString()));
            if (uri.isPresent()) {
                return uri.get();
            }
        }
        return null;
    }

    /**
     * Clients open this link, so anything else, such as a {@code javascript:} URL, is left out.
     */
    private static boolean isWebPage(URI uri) {
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
    }

    /**
     * Mastodon sends the same text in {@code content} and under its language in {@code contentMap}. A note in several
     * languages can't be told apart by which one {@code content} is, so the first valid one is taken.
     */
    private static String language(JsonNode contentMap) {
        if (!contentMap.isObject()) {
            return null;
        }
        return contentMap.properties().stream()
                .map(Map.Entry::getKey)
                .filter(key -> !key.equals("und") && StatusService.isLanguageCode(key))
                .findFirst()
                .orElse(null);
    }

    static Instant timestamp(JsonNode node) {
        var text = ActivityJson.text(node);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
