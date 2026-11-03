package dev.biribiri.elysium.actor;

import java.util.Optional;
import java.util.UUID;

/**
 * Parses the IDs of actors, statuses and other records. This is in the {@code actor} module because every module that
 * reads IDs from a request can already depend on it.
 */
public final class Ids {

    private Ids() {
    }

    /**
     * Parses an ID, or returns empty if it's null or isn't one. {@link UUID#fromString} alone also accepts values like
     * {@code 1-1-1-1-1}.
     */
    public static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            var parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? Optional.of(parsed) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
