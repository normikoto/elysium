package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * Selects a page of the list of accounts. Deleted accounts are never listed. Results are in order of ID, descending, which is
 * only a stable order to page through, because the IDs of actors are random.
 *
 * @param local          only local accounts, if true, and only remote ones, if false. Null for both.
 * @param suspendedOnly  whether to only list suspended accounts
 * @param usernamePrefix only accounts whose username starts with this, ignoring case, or null
 * @param domain         only accounts on this domain, or null
 * @param limit          the page size, clamped to 1 to {@value MAX_LIMIT}
 * @param maxId          only return accounts that sort before this, or null
 * @param sinceId        only return accounts that sort after this, starting from the last, or null
 * @param minId          only return accounts that sort after this, starting from the closest to it, or null. Takes priority
 *                       over {@code sinceId}.
 */
public record AccountFilter(
        Boolean local,
        boolean suspendedOnly,
        String usernamePrefix,
        String domain,
        int limit,
        UUID maxId,
        UUID sinceId,
        UUID minId
) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;

    public AccountFilter {
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }
}
