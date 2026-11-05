package dev.biribiri.elysium.actor;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Parses and generates the IDs of actors, statuses and other records. This is in the {@code actor} module because every
 * module that reads IDs from a request can already depend on it.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /**
     * Generates a UUIDv7, which PostgreSQL sorts by time, for records that are paged through in that order.
     * {@link UUID#compareTo} compares signed values, so these can only be ordered in SQL.
     */
    public static UUID timeOrdered(Instant timestamp) {
        var mostSignificant = (timestamp.toEpochMilli() << 16) | 0x7000L | RANDOM.nextInt(1 << 12);
        var leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
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
