package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * Selects one page of the accounts that favourited or boosted a status, paged by the favourites' or boosts' IDs and
 * returned newest first. The limits are Mastodon's for lists of accounts.
 *
 * @param minId takes priority over {@code sinceId}, and starts from the closest to it
 */
public record AccountPage(int limit, UUID maxId, UUID sinceId, UUID minId) {

    public static final int DEFAULT_LIMIT = 40;
    public static final int MAX_LIMIT = 80;

    public AccountPage {
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }
}
