package dev.biribiri.elysium.content;

import java.util.Locale;
import java.util.Set;

/**
 * Who a status's author approves quotes from, as FEP-044f's {@code canQuote} says it. Only advice for clients: whether a
 * quote is approved is decided by its {@link QuoteState}.
 *
 * @param automatic whose quotes are approved without the author looking at them
 * @param manual    whose quotes the author decides on
 */
public record QuoteApproval(Set<Audience> automatic, Set<Audience> manual) {

    public QuoteApproval {
        automatic = Set.copyOf(automatic);
        manual = Set.copyOf(manual);
    }

    public enum Audience {
        PUBLIC,
        /** The author's followers. */
        FOLLOWERS,
        /** The accounts the author follows. */
        FOLLOWING,
        /** Anything else, such as one actor, which Mastodon's clients can't show either. */
        UNSUPPORTED;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The policy a local status has this approval for, or {@link QuotePolicy#NOBODY} if it's one a local status can't have.
     */
    public QuotePolicy policy() {
        if (automatic.contains(Audience.PUBLIC)) {
            return QuotePolicy.PUBLIC;
        }
        return automatic.contains(Audience.FOLLOWERS) ? QuotePolicy.FOLLOWERS : QuotePolicy.NOBODY;
    }
}
