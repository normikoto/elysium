package dev.biribiri.elysium.api.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formats times the way Mastodon's API does.
 */
final class ApiTimestamps {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ApiTimestamps() {
    }

    static String format(Instant instant) {
        return TIMESTAMP.format(instant);
    }
}
