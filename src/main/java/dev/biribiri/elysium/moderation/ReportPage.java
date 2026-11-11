package dev.biribiri.elysium.moderation;

import java.util.UUID;

/**
 * Selects one page of reports, newest first.
 *
 * @param resolved   whether to list resolved reports rather than open ones
 * @param reporterId only reports by this account, or null
 * @param targetId   only reports about this account, or null
 * @param limit      the page size, clamped to 1 to {@value MAX_LIMIT}
 * @param maxId      only reports older than this, or null
 * @param sinceId    only reports newer than this, starting from the newest, or null
 * @param minId      only reports newer than this, starting from the closest to it, or null. Takes priority over
 *                   {@code sinceId}.
 */
public record ReportPage(boolean resolved, UUID reporterId, UUID targetId, int limit, UUID maxId, UUID sinceId,
                         UUID minId) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;

    public ReportPage {
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }
}
