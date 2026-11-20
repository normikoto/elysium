package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What happens to the quotes of a local status that its {@link QuotePolicy} doesn't approve automatically.
 */
public enum OtherQuotes {
    REJECT,
    /** Its author approves or rejects them by hand. */
    REVIEW;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<OtherQuotes> fromValue(String value) {
        return Arrays.stream(values()).filter(others -> others.value().equals(value)).findFirst();
    }
}
