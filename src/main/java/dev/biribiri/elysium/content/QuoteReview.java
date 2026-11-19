package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Whose quotes of a local status its author approves by hand, among those its {@link QuotePolicy} lets quote it. Everyone
 * else's are approved automatically.
 */
public enum QuoteReview {
    NONE,
    /** Accounts that don't follow the author, so that followers' quotes are approved automatically. */
    NON_FOLLOWERS,
    ALL;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<QuoteReview> fromValue(String value) {
        return Arrays.stream(values()).filter(review -> review.value().equals(value)).findFirst();
    }
}
