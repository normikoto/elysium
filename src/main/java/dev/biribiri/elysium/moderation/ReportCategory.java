package dev.biribiri.elysium.moderation;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why an account was reported, with the names Mastodon's API uses. There's no {@code violation}, which names the server's
 * rules, since this server doesn't have any.
 */
public enum ReportCategory {
    SPAM("spam"),
    LEGAL("legal"),
    OTHER("other");

    private final String value;

    ReportCategory(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ReportCategory> fromValue(String value) {
        return Arrays.stream(values()).filter(category -> category.value.equals(value)).findFirst();
    }
}
