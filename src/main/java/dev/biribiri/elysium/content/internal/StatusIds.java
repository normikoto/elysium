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

    /**
     * The ID for a status that's being stored now. Clients poll for statuses newer than the last ID they saw, so a status
     * must never become visible after one with a later ID, or they would miss it. The lock this takes is held until commit,
     * which makes IDs and commits happen in the same order.
     */
    public static UUID next(StatusRepository statuses, Instant now) {
        statuses.lockIdAssignment();
        return statuses.findNewestId()
                .map(newest -> after(newest, now))
                .orElseGet(() -> fromTimestamp(now));
    }

    /**
     * The ID for an older status that's only being stored now, from when it was posted. Takes the same lock as
     * {@link #next}, because one dated close to now could otherwise commit ahead of a status that was given an earlier ID,
     * which clients would then miss. A date before 1970 doesn't fit in an ID, and would make one that sorts after every
     * other, so that every later ID would be worked out from it.
     */
    public static UUID backfilled(StatusRepository statuses, Instant published, Instant now) {
        statuses.lockIdAssignment();
        var timestamp = published.isAfter(now) ? now : published.isBefore(Instant.EPOCH) ? Instant.EPOCH : published;
        return fromTimestamp(timestamp);
    }
}
