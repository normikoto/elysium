package dev.biribiri.elysium.content.internal;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates UUIDv7 status IDs, which PostgreSQL sorts by time. Pagination relies on that order. {@link UUID#compareTo}
 * compares signed values, so these can only be ordered in SQL.
 */
public final class StatusIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private StatusIds() {
    }

    /**
     * Takes the time from the status rather than the clock, so that a status imported later still sorts by when it was
     * published.
     */
    public static UUID fromTimestamp(Instant timestamp) {
        return generate(timestamp.toEpochMilli());
    }

    /**
     * Generates an ID that sorts after {@code previous}, even if they share a millisecond or the clock has moved back.
     */
    public static UUID after(UUID previous, Instant timestamp) {
        var previousMillis = previous.getMostSignificantBits() >>> 16;
        return generate(Math.max(timestamp.toEpochMilli(), previousMillis + 1));
    }

    private static UUID generate(long millis) {
        var mostSignificant = (millis << 16) | 0x7000L | RANDOM.nextInt(1 << 12);
        var leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
