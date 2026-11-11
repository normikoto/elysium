package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Who can quote a local status, as Mastodon offers it. Approval is always automatic.
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

    QuoteApproval approval() {
        return switch (this) {
            case PUBLIC -> new QuoteApproval(Set.of(QuoteApproval.Audience.PUBLIC), Set.of());
            case FOLLOWERS -> new QuoteApproval(Set.of(QuoteApproval.Audience.FOLLOWERS), Set.of());
            case NOBODY -> new QuoteApproval(Set.of(), Set.of());
        };
    }
}
