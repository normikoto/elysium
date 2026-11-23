package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteProfile;
import dev.biribiri.elysium.content.RemoteEmoji;
import dev.biribiri.elysium.text.RemoteHtml;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what to believe from another server's actor document. Activities get sent to where it says, so a document is
 * refused unless those are all usable. Its profile is only shown, so a part of that which can't be used is left out
 * instead. Failure messages are shown to admins, so they don't quote the document.
 */
final class RemoteActorDocument {

    private static final int MAX_USERNAME_LENGTH = 255;

    /**
     * Limits on a profile, well above what Mastodon, GoToSocial and Misskey let their own users write, so that only a
     * profile that's meant to be a nuisance loses anything. Names are cut short, and a bio or field that's too long is
     * left out, since cutting HTML short could leave it saying something else.
     */
    static final int MAX_DISPLAY_NAME = 200;
    static final int MAX_NOTE_HTML = 20_000;
    static final int MAX_FIELDS = 16;
    static final int MAX_FIELD_NAME = 255;
    static final int MAX_FIELD_VALUE_HTML = 2_000;

    /**
     * Mastodon lets its users list as many as they like, but a move only needs the one being moved from.
     */
    static final int MAX_ALIASES = 20;

    private static final Set<String> BOT_TYPES = Set.of("Service", "Application");

    private RemoteActorDocument() {
    }

    /**
     * @return the host and port that would be stored for a remote actor, in the same form as this server's authority
     */
    static String domainOf(URI actor) {
        return HostHeader.value(actor.getScheme(), actor.getHost(), actor.getPort());
    }

    /**
     * @param uri can come from another server, so it can be a relative reference or have no host
     * @return whether the URI is on this domain, as {@link #domainOf} gives it
     */
    static boolean isOnDomain(URI uri, String domain) {
        return uri.getScheme() != null && uri.getHost() != null && domainOf(uri).equals(domain);
    }

    /**
     * @param uri      the actor being looked up, which was named by a valid signature
     * @param document what its server answered with
     * @throws PermanentJobFailure if the document can't be used
     */
    static RemoteActorProfile read(URI uri, JsonNode document) {
        return read(uri, document, null);
    }

    /**
     * @param alias an account to keep in {@code alsoKnownAs} if the document lists it, even past {@link #MAX_ALIASES}, for
     *              a fetch made to check whether a move from that account is allowed. Null for none.
     */
    static RemoteActorProfile read(URI uri, JsonNode document, URI alias) {
        if (!uri.toString().equals(document.path("id").stringValue(null))) {
            throw refused(uri, "has a different id");
        }
        if (!ActivityDocuments.isActor(document)) {
            throw refused(uri, "isn't an actor");
        }
        var username = document.path("preferredUsername").stringValue(null);
        if (username == null || username.isEmpty() || username.length() > MAX_USERNAME_LENGTH
                || username.chars().anyMatch(Character::isISOControl)) {
            throw refused(uri, "doesn't have a usable preferredUsername");
        }
        var domain = domainOf(uri);
        var inbox = onActorsServer(uri, document.path("inbox").stringValue(null));
        if (inbox == null) {
            throw refused(uri, "doesn't have an inbox that's an HTTPS URL on the actor's host");
        }
        // Nothing sends to this yet, so a bad one is a reason to leave it out rather than to lose the actor.
        var sharedInbox = onActorsServer(uri, document.path("endpoints").path("sharedInbox").stringValue(null));
        // Only used to see who an activity is addressed to, so a bad one is left out too. One on another host could make
        // an activity look addressed to the followers of someone else.
        var followers = onActorsServer(uri, document.path("followers").stringValue(null));
        // Backfill fetches it, and trusts what it embeds as the actor's, so it has to be the actor's server's word.
        var outbox = onActorsServer(uri, document.path("outbox").stringValue(null));
        // Pins are read from it, and its Adds and Removes have to name it, so it has to be the actor's server's word too.
        var featured = onActorsServer(uri, ActivityJson.idOf(document.path("featured")));
        return new RemoteActorProfile(
                uri,
                username,
                domain,
                inbox,
                outbox,
                sharedInbox,
                publicKeyPem(document.path("publicKey")),
                followers,
                featured,
                profile(uri, document, alias));
    }

    /**
     * The custom emoji that an actor's profile uses, which are stored under its domain.
     */
    static List<RemoteEmoji> emoji(JsonNode document) {
        return RemoteEmojis.read(document.path("tag"));
    }

    private static RemoteProfile profile(URI uri, JsonNode document, URI alias) {
        var types = ActivityJson.types(document.path("type"));
        var noteHtml = html(ActivityJson.naturalText(document, "summary"), MAX_NOTE_HTML).orElse("");
        return new RemoteProfile(
                cut(plain(ActivityJson.naturalText(document, "name")), MAX_DISPLAY_NAME),
                RemoteHtml.plainText(noteHtml),
                noteHtml,
                RemoteEmojis.iconUrl(document.path("icon")).orElse(null),
                RemoteEmojis.iconUrl(document.path("image")).orElse(null),
                fields(document.path("attachment")),
                ActivityJson.flag(document.path("manuallyApprovesFollowers")),
                ActivityJson.flag(document.path("indexable")),
                types.stream().anyMatch(BOT_TYPES::contains),
                ActivityJson.flag(document.path("isCat")),
                types.contains("Group"),
                RemoteNote.timestamp(document.path("published")),
                profileUrl(document.path("url")),
                aliases(uri, document.path("alsoKnownAs"), alias),
                otherActor(uri, ActivityJson.idOf(document.path("movedTo"))));
    }

    /**
     * Other actors' URIs, which are only believed once those actors' own documents agree, so they can be on any server.
     */
    private static List<URI> aliases(URI uri, JsonNode alsoKnownAs, URI keep) {
        var all = ActivityJson.ids(alsoKnownAs).stream()
                .map(id -> otherActor(uri, id))
                .filter(alias -> alias != null)
                .distinct()
                .toList();
        if (all.size() <= MAX_ALIASES) {
            return all;
        }
        var kept = new ArrayList<>(all.subList(0, MAX_ALIASES));
        // Servers don't agree on an order, so the one a move is checked for could be anywhere in a long list.
        if (keep != null && all.contains(keep) && !kept.contains(keep)) {
            kept.set(MAX_ALIASES - 1, keep);
        }
        return kept;
    }

    /**
     * @return the URI of an actor other than {@code uri}, or null if it isn't an HTTPS URL that can be stored
     */
    private static URI otherActor(URI uri, String value) {
        if (value == null || !InboundActivity.isStorableId(value)) {
            return null;
        }
        return ActivityObject.parseUri(value)
                .filter(other -> "https".equalsIgnoreCase(other.getScheme()) && other.getHost() != null)
                .filter(other -> !other.equals(uri))
                .orElse(null);
    }

    /**
     * Mastodon's profile fields, which are {@code PropertyValue}s whose value is HTML.
     */
    private static List<RemoteProfile.Field> fields(JsonNode attachment) {
        var fields = new ArrayList<RemoteProfile.Field>();
        for (var candidate : attachment.isArray() ? attachment.valueStream().toList() : List.of(attachment)) {
            if (fields.size() == MAX_FIELDS) {
                break;
            }
            if (!candidate.isObject() || !ActivityJson.types(candidate.path("type")).contains("PropertyValue")) {
                continue;
            }
            var name = plain(ActivityJson.text(candidate.path("name")));
            var value = html(ActivityJson.text(candidate.path("value")), MAX_FIELD_VALUE_HTML);
            if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_FIELD_NAME || value.isEmpty()) {
                continue;
            }
            fields.add(new RemoteProfile.Field(name, RemoteHtml.inlineText(value.get()), value.get()));
        }
        return fields;
    }

    /**
     * Names are plain text in ActivityStreams, and they're shown on one line.
     */
    private static String plain(String text) {
        if (text == null) {
            return "";
        }
        return text.codePoints()
                .map(c -> Character.isISOControl(c) ? ' ' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String cut(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max)).strip();
    }

    /**
     * @return the sanitized HTML, which is empty if there's none, or nothing if it's too long
     */
    private static Optional<String> html(String html, int max) {
        if (html == null) {
            return Optional.of("");
        }
        var sanitized = RemoteHtml.sanitize(html);
        return sanitized.length() > max ? Optional.empty() : Optional.of(sanitized);
    }

    /**
     * Mastodon links its profile page as a plain URL, and others can send a {@code Link} or several. The first one that
     * browsers can open is taken.
     */
    private static URI profileUrl(JsonNode url) {
        for (var candidate : url.isArray() ? url.valueStream().toList() : List.of(url)) {
            var href = candidate.isObject() ? ActivityJson.text(candidate.path("href")) : ActivityJson.text(candidate);
            var parsed = href == null || !InboundActivity.isStorableId(href)
                    ? null
                    : ActivityObject.parseUri(href)
                            .filter(uri -> uri.getHost() != null
                                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                            .orElse(null);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /**
     * Inboxes are where this server sends requests when it's told to, so one on another host would let any server aim this
     * one at a third party. Followers, featured collections, and outboxes are checked the same way.
     *
     * @return the URI, or null if there isn't one that can be used
     */
    private static URI onActorsServer(URI actor, String value) {
        if (value == null || !InboundActivity.isStorableId(value)) {
            return null;
        }
        return ActivityObject.parseUri(value).filter(inbox -> Inbox.isOnActorsServer(inbox, actor)).orElse(null);
    }

    /**
     * An actor can have one key or several, and each can be just an ID. This stores the first one that's included.
     */
    private static String publicKeyPem(JsonNode publicKey) {
        if (publicKey.isArray()) {
            return publicKey.valueStream()
                    .map(RemoteActorDocument::publicKeyPem)
                    .filter(pem -> pem != null)
                    .findFirst()
                    .orElse(null);
        }
        var pem = publicKey.path("publicKeyPem").stringValue(null);
        // The database can't store a NUL, and a real key doesn't have one.
        return pem == null || pem.indexOf('\0') != -1 ? null : pem;
    }

    private static PermanentJobFailure refused(URI actor, String problem) {
        return new PermanentJobFailure("The actor document of " + actor + " " + problem);
    }
}
