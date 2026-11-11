package dev.biribiri.elysium.content;

import java.util.Locale;

/**
 * What would happen to a quote of a status by one account, as Mastodon's {@code quote_approval.current_user} says it.
 */
public enum QuoteDecision {
    AUTOMATIC,
    MANUAL,
    DENIED,
    /** The status's policy names an audience this server doesn't know how to check. */
    UNKNOWN;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
