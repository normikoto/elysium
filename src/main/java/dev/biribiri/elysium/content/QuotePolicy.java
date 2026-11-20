package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Whose quotes of a local status are approved automatically, as Mastodon offers it. Whether everyone else's are rejected
 * or reviewed by its author is chosen apart from this, with {@link OtherQuotes}.
 */
public enum QuotePolicy {
    PUBLIC,
    /** Its author's followers, and its author. */
    FOLLOWERS,
    /** Only its author. */
    NOBODY;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<QuotePolicy> fromValue(String value) {
        return Arrays.stream(values()).filter(policy -> policy.value().equals(value)).findFirst();
    }

    /**
     * Everyone's quotes of a {@link #PUBLIC} status are approved automatically, so {@code others} changes nothing for one.
     */
    QuoteApproval approval(OtherQuotes others) {
        var reviewed = others == OtherQuotes.REVIEW
                ? Set.of(QuoteApproval.Audience.PUBLIC) : Set.<QuoteApproval.Audience>of();
        return switch (this) {
            case PUBLIC -> new QuoteApproval(Set.of(QuoteApproval.Audience.PUBLIC), Set.of());
            case FOLLOWERS -> new QuoteApproval(Set.of(QuoteApproval.Audience.FOLLOWERS), reviewed);
            case NOBODY -> new QuoteApproval(Set.of(), reviewed);
        };
    }
}
