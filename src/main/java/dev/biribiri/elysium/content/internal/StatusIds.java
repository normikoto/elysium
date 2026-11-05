package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;

import java.time.Instant;
import java.util.UUID;

/**
 * Generates status IDs, which PostgreSQL sorts by time. Pagination relies on that order.
 */
public final class StatusIds {

    private StatusIds() {
    }

    /**
     * Takes the time from the status rather than the clock, so that a status imported later still sorts by when it was
     * published.
     */
    public static UUID fromTimestamp(Instant timestamp) {
        return Ids.timeOrdered(timestamp);
    }

    /**
     * Generates an ID that sorts after {@code previous}, even if they share a millisecond or the clock has moved back.
     */
    public static UUID after(UUID previous, Instant timestamp) {
        var previousMillis = previous.getMostSignificantBits() >>> 16;
        return Ids.timeOrdered(Instant.ofEpochMilli(Math.max(timestamp.toEpochMilli(), previousMillis + 1)));
    }
}
