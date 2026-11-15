package dev.biribiri.elysium.api.streaming;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.text.Hashtags;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One stream a connection can subscribe to, by Mastodon's name for it.
 *
 * @param tag    the hashtag's normalized name, for a hashtag stream, or null
 * @param listId the list, for a list stream, or null
 */
public record StreamKey(Kind kind, StatusOrigin origin, boolean onlyMedia, String tag, UUID listId) {

    public enum Kind {
        /** The home timeline, and notifications if the token can read them. */
        USER,
        NOTIFICATIONS,
        DIRECT,
        PUBLIC,
        HASHTAG,
        LIST;

        /** Whether it carries statuses, so their deletions too. */
        boolean hasStatuses() {
            return this == USER || this == PUBLIC || this == HASHTAG || this == LIST;
        }
    }

    /**
     * Thrown for a stream that can't be subscribed to, with a message that's sent to the client as is.
     */
    public static class InvalidStreamException extends RuntimeException {

        private final int status;

        InvalidStreamException(String message) {
            this(message, 400);
        }

        public InvalidStreamException(String message, int status) {
            super(message);
            this.status = status;
        }

        /**
         * The HTTP status Mastodon gives for it.
         */
        public int status() {
            return status;
        }
    }

    /**
     * Reads a stream that isn't a list's, as Mastodon names it.
     *
     * @throws InvalidStreamException if the name is unknown, or a hashtag stream has no valid tag
     */
    public static StreamKey parse(String name, String tag) {
        return parse(name, tag, null);
    }

    /**
     * Reads a stream as Mastodon names it.
     *
     * @param list the {@code list} parameter, for a list stream
     * @throws InvalidStreamException if the name is unknown, a hashtag stream has no valid tag, or a list stream no list.
     *                                A list ID that isn't a UUID is refused as Mastodon refuses someone else's list.
     */
    public static StreamKey parse(String name, String tag, String list) {
        if (name == null) {
            throw new InvalidStreamException("Unknown stream type");
        }
        return switch (name) {
            case "user" -> new StreamKey(Kind.USER, StatusOrigin.ANY, false, null, null);
            case "user:notification" -> new StreamKey(Kind.NOTIFICATIONS, StatusOrigin.ANY, false, null, null);
            case "direct" -> new StreamKey(Kind.DIRECT, StatusOrigin.ANY, false, null, null);
            case "public" -> new StreamKey(Kind.PUBLIC, StatusOrigin.ANY, false, null, null);
            case "public:media" -> new StreamKey(Kind.PUBLIC, StatusOrigin.ANY, true, null, null);
            case "public:local" -> new StreamKey(Kind.PUBLIC, StatusOrigin.LOCAL, false, null, null);
            case "public:local:media" -> new StreamKey(Kind.PUBLIC, StatusOrigin.LOCAL, true, null, null);
            case "public:remote" -> new StreamKey(Kind.PUBLIC, StatusOrigin.REMOTE, false, null, null);
            case "public:remote:media" -> new StreamKey(Kind.PUBLIC, StatusOrigin.REMOTE, true, null, null);
            case "hashtag" -> hashtag(StatusOrigin.ANY, tag);
            case "hashtag:local" -> hashtag(StatusOrigin.LOCAL, tag);
            case "list" -> list(list);
            default -> throw new InvalidStreamException("Unknown stream type");
        };
    }

    private static StreamKey hashtag(StatusOrigin origin, String tag) {
        if (tag == null || tag.isBlank()) {
            throw new InvalidStreamException("Missing tag name parameter");
        }
        var parsed = Hashtags.parse(tag)
                .orElseThrow(() -> new InvalidStreamException("Invalid tag name parameter"));
        return new StreamKey(Kind.HASHTAG, origin, false, parsed, null);
    }

    private static StreamKey list(String list) {
        if (list == null || list.isEmpty()) {
            throw new InvalidStreamException("Missing list name parameter");
        }
        var listId = Ids.parse(list)
                .orElseThrow(() -> new InvalidStreamException(Refusal.LIST.message, Refusal.LIST.status));
        return new StreamKey(Kind.LIST, StatusOrigin.ANY, false, null, listId);
    }

    /**
     * Mastodon's name for it, as each message's {@code stream} gives it.
     */
    public String name() {
        return switch (kind) {
            case USER -> "user";
            case NOTIFICATIONS -> "user:notification";
            case DIRECT -> "direct";
            case PUBLIC -> "public" + suffix(origin) + (onlyMedia ? ":media" : "");
            case HASHTAG -> "hashtag" + suffix(origin);
            case LIST -> "list";
        };
    }

    /**
     * The {@code stream} of each message, which names the tag or list too, so that a client can tell its hashtag and
     * list streams apart.
     */
    public List<String> wireName() {
        if (tag != null) return List.of(name(), tag);
        if (listId != null) return List.of(name(), listId.toString());
        return List.of(name());
    }

    /**
     * Whether the token can read this stream, with the same scopes the REST endpoint for its timeline needs.
     *
     * @return why not, if it can't, with the HTTP status Mastodon gives for it
     */
    Optional<Refusal> refusal(Optional<?> viewer, Set<String> scopes, boolean anonymous) {
        return switch (kind) {
            // Mastodon's list stream needs read:statuses, though its timeline needs read:lists.
            case USER, DIRECT, LIST -> userStream(viewer, scopes, anonymous, "read:statuses");
            case NOTIFICATIONS -> userStream(viewer, scopes, anonymous, "read:notifications");
            case PUBLIC, HASHTAG -> anonymous || scopes.contains("read:statuses")
                    ? Optional.empty() : Optional.of(Refusal.SCOPE);
        };
    }

    private static Optional<Refusal> userStream(Optional<?> viewer, Set<String> scopes, boolean anonymous,
                                                String scope) {
        if (anonymous) return Optional.of(Refusal.TOKEN);
        if (!scopes.contains(scope)) return Optional.of(Refusal.SCOPE);
        if (viewer.isEmpty()) return Optional.of(Refusal.USER);
        return Optional.empty();
    }

    /**
     * Why a stream was refused, with the message and status Mastodon sends.
     */
    public enum Refusal {
        TOKEN(401, "Missing access token"),
        SCOPE(403, "Access token does not have the required scopes"),
        USER(422, "This stream requires an authenticated user"),
        /** For a list that isn't the viewer's, or doesn't exist. */
        LIST(401, "Not authorized to stream this list");

        final int status;
        final String message;

        Refusal(int status, String message) {
            this.status = status;
            this.message = message;
        }

        public int status() {
            return status;
        }

        public String message() {
            return message;
        }
    }

    private static String suffix(StatusOrigin origin) {
        return switch (origin) {
            case ANY -> "";
            case LOCAL -> ":local";
            case REMOTE -> ":remote";
        };
    }
}
