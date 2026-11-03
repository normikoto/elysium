package dev.biribiri.elysium.content;

import java.time.Instant;

/**
 * An account's public posting activity. Direct statuses aren't counted, because either value would reveal them.
 *
 * @param lastStatusAt when the latest status was published, or null if there are none
 */
public record AccountStats(long statusesCount, Instant lastStatusAt) {

    public static final AccountStats EMPTY = new AccountStats(0, null);
}
