package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A user's filter of statuses, by keywords in their text or by the statuses themselves.
 *
 * @param expiresAt when it stops applying, or null if it doesn't
 */
public record PostFilter(
        UUID id,
        String title,
        Set<Context> contexts,
        Action action,
        Instant expiresAt,
        List<Keyword> keywords,
        List<FilteredStatus> statuses) {

    public PostFilter {
        contexts = Set.copyOf(contexts);
        keywords = List.copyOf(keywords);
        statuses = List.copyOf(statuses);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /**
     * Where a filter applies, by Mastodon's names.
     */
    public enum Context {
        HOME, NOTIFICATIONS, PUBLIC, THREAD, ACCOUNT;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * @throws InvalidPostFilterException if there's no context with this name
         */
        public static Context fromValue(String value) {
            return Arrays.stream(values()).filter(context -> context.value().equals(value)).findFirst()
                    .orElseThrow(() -> new InvalidPostFilterException("Context None or invalid context supplied"));
        }
    }

    /**
     * What clients do with a status that matches, and {@link #HIDE} also leaves it out of lists here.
     */
    public enum Action {
        WARN, HIDE, BLUR;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * @throws InvalidPostFilterException if there's no action with this name
         */
        public static Action fromValue(String value) {
            return Arrays.stream(values()).filter(action -> action.value().equals(value)).findFirst()
                    .orElseThrow(() -> new InvalidPostFilterException("Filter action is not recognized"));
        }
    }

    /**
     * @param wholeWord whether the keyword only matches where it isn't part of a longer word
     */
    public record Keyword(UUID id, String keyword, boolean wholeWord) {
    }

    public record FilteredStatus(UUID id, UUID statusId) {
    }
}
