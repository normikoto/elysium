package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.Focus;
import dev.biribiri.elysium.content.RemoteAttachment;
import dev.biribiri.elysium.content.RemoteQuote;
import dev.biribiri.elysium.content.StatusService;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
 * @param attachments  what's in its {@code attachment} that has an HTTP or HTTPS URL, up to {@value #MAX_ATTACHMENTS}
 * @param quote        what it quotes, or null if it doesn't say
 * @param quotePolicy  its {@code interactionPolicy.canQuote}, or null if it has none
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
        List<String> hashtags,
        List<RemoteAttachment> attachments,
        RemoteQuote quote,
        QuotePolicy quotePolicy) {

    /**
     * The properties that say what a note quotes, in the order Mastodon reads them. Only FEP-044f's comes with a way to ask
     * for consent, so a note with only the others is from a server that can't ask.
     */
    private static final List<String> QUOTE_PROPERTIES = List.of("quote", "_misskey_quote", "quoteUrl", "quoteUri");

    /**
     * FEP-e232's {@code rel} for a quote, under both of the spellings that Misskey's namespace has been given.
     */
    private static final Set<String> QUOTE_RELS =
            Set.of("https://misskey-hub.net/ns#_misskey_quote", "https://misskey-hub.net/ns/#_misskey_quote");

    /**
     * FEP-044f's {@code canQuote}, as its server gave it.
     *
     * @param automaticApproval the IDs whose quotes are approved automatically
     * @param manualApproval    the IDs whose quotes its author decides on
     */
    record QuotePolicy(List<String> automaticApproval, List<String> manualApproval) {
    }

    /**
     * Each mention is looked up, so a note can't make this server look up any number of them. Mastodon has no limit, but
     * a note mentioning more accounts than this would be spam.
     */
    static final int MAX_MENTIONS = 100;

    /**
     * Misskey's limit, the highest of the common servers', so that none of theirs are cut short. Mastodon shows 4.
     */
    static final int MAX_ATTACHMENTS = 16;

    private static final int MAX_DIMENSION = 100_000;

    private static final Pattern BLURHASH = Pattern.compile("[0-9A-Za-z#$%*+,\\-.:;=?@\\[\\]^_{|}~]{6,100}");

    /**
     * How far ahead of this server's clock an {@code updated} can be, which is what signatures allow.
     */
    private static final Duration CLOCK_SKEW = Duration.ofHours(1);

    RemoteNote {
        attributedTo = List.copyOf(attributedTo);
        mentions = List.copyOf(mentions);
        hashtags = List.copyOf(hashtags);
        attachments = List.copyOf(attachments);
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
                hashtags(node.path("tag")),
                attachments(node.path("attachment")),
                quote(node),
                quotePolicy(node.path("interactionPolicy").path("canQuote"))));
    }

    private static RemoteQuote quote(JsonNode node) {
        URI quoted = null;
        for (var property : QUOTE_PROPERTIES) {
            quoted = firstId(node.path(property));
            if (quoted != null) {
                break;
            }
        }
        if (quoted == null) {
            quoted = ActivityJson.tags(node.path("tag")).stream()
                    .filter(tag -> "Link".equals(tag.type()) && tag.href() != null)
                    .filter(tag -> linkRels(node.path("tag"), tag.href()).stream().anyMatch(QUOTE_RELS::contains))
                    .flatMap(tag -> ActivityObject.parseUri(tag.href()).stream())
                    .findFirst()
                    .orElse(null);
        }
        if (quoted == null || !InboundActivity.isStorableId(quoted.toString()) || !isWebPage(quoted)) {
            return null;
        }
        var authorization = firstId(node.path("quoteAuthorization"));
        return new RemoteQuote(quoted, !node.has("quote"), authorization);
    }

    /**
     * The first value of a property that can be an ID, an object with one, or an array of them, as Mastodon reads it.
     */
    private static URI firstId(JsonNode node) {
        var first = node.isArray() ? node.path(0) : node;
        return Optional.ofNullable(ActivityJson.idOf(first)).flatMap(ActivityObject::parseUri).orElse(null);
    }

    /**
     * The {@code rel} of the {@code Link} tags with this {@code href}, which can be a string or an array.
     */
    private static List<String> linkRels(JsonNode tag, String href) {
        var rels = new ArrayList<String>();
        for (var candidate : tag.isArray() ? tag.valueStream().toList() : List.of(tag)) {
            if (candidate.isObject() && href.equals(ActivityJson.idOf(candidate.path("href")))) {
                var rel = candidate.path("rel");
                for (var value : rel.isArray() ? rel.valueStream().toList() : List.of(rel)) {
                    Optional.ofNullable(ActivityJson.text(value)).ifPresent(rels::add);
                }
            }
        }
        return rels;
    }

    /**
     * An empty array is the same as none in JSON-LD, which FEP-044f points out, so a policy with neither is none.
     */
    private static QuotePolicy quotePolicy(JsonNode canQuote) {
        if (!canQuote.isObject()) {
            return null;
        }
        var automatic = ActivityJson.ids(canQuote.path("automaticApproval"));
        var manual = ActivityJson.ids(canQuote.path("manualApproval"));
        return automatic.isEmpty() && manual.isEmpty() ? null : new QuotePolicy(automatic, manual);
    }

    /**
     * {@code attachment} can be one object or an array. Mastodon sends a {@code Document} for each, and others send
     * {@code Image}, {@code Video}, or {@code Audio}. Its {@code mediaType} is only what its server says, since the file
     * isn't read here. One without a URL that can be opened is left out.
     */
    private static List<RemoteAttachment> attachments(JsonNode node) {
        var attachments = new ArrayList<RemoteAttachment>();
        for (var candidate : node.isArray() ? node.valueStream().toList() : List.of(node)) {
            if (attachments.size() == MAX_ATTACHMENTS) {
                break;
            }
            if (!candidate.isObject()) {
                continue;
            }
            var types = ActivityJson.types(candidate.path("type"));
            if (types.stream().noneMatch(type -> Set.of("Document", "Image", "Video", "Audio").contains(type))) {
                continue;
            }
            var url = attachmentUrl(candidate.path("url"));
            if (url == null) {
                continue;
            }
            var mediaType = ActivityJson.text(candidate.path("mediaType"));
            attachments.add(new RemoteAttachment(
                    url,
                    attachmentType(mediaType, types),
                    mediaType == null || mediaType.length() > 255 ? null : mediaType,
                    ActivityJson.text(candidate.path("name")),
                    dimension(candidate.path("width")),
                    dimension(candidate.path("height")),
                    Optional.ofNullable(ActivityJson.text(candidate.path("blurhash")))
                            .filter(hash -> BLURHASH.matcher(hash).matches())
                            .orElse(null),
                    focus(candidate.path("focalPoint"))));
        }
        return attachments;
    }

    /**
     * Like a note's {@code url}, except that a {@code Link}'s {@code mediaType} is the file's, so any is taken.
     */
    private static URI attachmentUrl(JsonNode node) {
        for (var candidate : node.isArray() ? node.valueStream().toList() : List.of(node)) {
            var href = candidate.isObject() ? ActivityJson.text(candidate.path("href")) : ActivityJson.text(candidate);
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
     * The {@code mediaType} decides, as on Mastodon, and the object's type only when there's none.
     */
    private static AttachmentType attachmentType(String mediaType, List<String> types) {
        if (mediaType != null) {
            var lower = mediaType.toLowerCase(Locale.ROOT);
            if (lower.startsWith("image/")) {
                return AttachmentType.IMAGE;
            }
            if (lower.startsWith("video/")) {
                return AttachmentType.VIDEO;
            }
            if (lower.startsWith("audio/")) {
                return AttachmentType.AUDIO;
            }
            return AttachmentType.UNKNOWN;
        }
        return types.contains("Image") ? AttachmentType.IMAGE
                : types.contains("Video") ? AttachmentType.VIDEO
                : types.contains("Audio") ? AttachmentType.AUDIO
                : AttachmentType.UNKNOWN;
    }

    private static Integer dimension(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0 && node.intValue() <= MAX_DIMENSION
                ? node.intValue()
                : null;
    }

    private static Focus focus(JsonNode node) {
        if (!node.isArray() || node.size() != 2 || !node.get(0).isNumber() || !node.get(1).isNumber()) {
            return null;
        }
        var x = node.get(0).doubleValue();
        var y = node.get(1).doubleValue();
        return Double.isFinite(x) && Double.isFinite(y) ? new Focus(x, y) : null;
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
