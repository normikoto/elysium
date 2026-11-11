package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Locale;

/**
 * Whether a quote has its quoted author's consent. Only an accepted quote shows the status it quotes.
 */
public enum QuoteState {
    /** Waiting for the quoted author's server to answer, or for this server to check the answer. */
    PENDING,
    ACCEPTED,
    REJECTED,
    /** Accepted, and then withdrawn by the quoted author. */
    REVOKED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    static QuoteState fromValue(String value) {
        return Arrays.stream(values()).filter(state -> state.value().equals(value)).findFirst().orElseThrow();
    }
}
