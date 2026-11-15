package dev.biribiri.elysium.api.streaming;

import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.text.Hashtags;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One stream a connection can subscribe to, by Mastodon's name for it.
 *
 * @param tag the hashtag's normalized name, for a hashtag stream, or null
 */
public record StreamKey(Kind kind, StatusOrigin origin, boolean onlyMedia, String tag) {

    public enum Kind {
        /** The home timeline, and notifications if the token can read them. */
        USER,
        NOTIFICATIONS,
        DIRECT,
        PUBLIC,
        HASHTAG;

        /** Whether it carries statuses, so their deletions too. */
        boolean hasStatuses() {
            return this == USER || this == PUBLIC || this == HASHTAG;
        }
    }

    /**
     * Thrown for a stream that can't be subscribed to, with a message that's sent to the client as is.
     */
    public static class InvalidStreamException extends RuntimeException {
        InvalidStreamException(String message) {
            super(message);
        }
    }

    /**
     * Reads a stream as Mastodon names it. Lists aren't implemented, so {@code list} is unknown.
     *
     * @throws InvalidStreamException if the name is unknown, or a hashtag stream has no valid tag
     */
    public static StreamKey parse(String name, String tag) {
        if (name == null) {
            throw new InvalidStreamException("Unknown stream type");
        }
        return switch (name) {
            case "user" -> new StreamKey(Kind.USER, StatusOrigin.ANY, false, null);
            case "user:notification" -> new StreamKey(Kind.NOTIFICATIONS, StatusOrigin.ANY, false, null);
            case "direct" -> new StreamKey(Kind.DIRECT, StatusOrigin.ANY, false, null);
            case "public" -> new StreamKey(Kind.PUBLIC, StatusOrigin.ANY, false, null);
            case "public:media" -> new StreamKey(Kind.PUBLIC, StatusOrigin.ANY, true, null);
            case "public:local" -> new StreamKey(Kind.PUBLIC, StatusOrigin.LOCAL, false, null);
            case "public:local:media" -> new StreamKey(Kind.PUBLIC, StatusOrigin.LOCAL, true, null);
            case "public:remote" -> new StreamKey(Kind.PUBLIC, StatusOrigin.REMOTE, false, null);
            case "public:remote:media" -> new StreamKey(Kind.PUBLIC, StatusOrigin.REMOTE, true, null);
            case "hashtag" -> hashtag(StatusOrigin.ANY, tag);
            case "hashtag:local" -> hashtag(StatusOrigin.LOCAL, tag);
            default -> throw new InvalidStreamException("Unknown stream type");
        };
    }

    private static StreamKey hashtag(StatusOrigin origin, String tag) {
        if (tag == null || tag.isBlank()) {
            throw new InvalidStreamException("Missing tag name parameter");
        }
        var parsed = Hashtags.parse(tag)
                .orElseThrow(() -> new InvalidStreamException("Invalid tag name parameter"));
        return new StreamKey(Kind.HASHTAG, origin, false, parsed);
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
        };
    }

    /**
     * The {@code stream} of each message, which names the tag too, so that a client can tell its hashtag streams apart.
     */
    public List<String> wireName() {
        return tag == null ? List.of(name()) : List.of(name(), tag);
    }

    /**
     * Whether the token can read this stream, with the same scopes the REST endpoint for its timeline needs.
     *
     * @return why not, if it can't, with the HTTP status Mastodon gives for it
     */
    Optional<Refusal> refusal(Optional<?> viewer, Set<String> scopes, boolean anonymous) {
        return switch (kind) {
            case USER, DIRECT -> userStream(viewer, scopes, anonymous, "read:statuses");
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
        USER(422, "This stream requires an authenticated user");

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
