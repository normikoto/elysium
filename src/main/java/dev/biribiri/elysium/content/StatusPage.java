package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * Selects one page of a list of statuses. Results are always returned newest first.
 *
 * @param limit   the page size, clamped to 1 to {@value MAX_LIMIT}
 * @param maxId   only return statuses older than this, or null
 * @param sinceId only return statuses newer than this, starting from the newest, or null
 * @param minId   only return statuses newer than this, starting from the closest to it, or null. Takes priority over
 *                {@code sinceId}.
 */
public record StatusPage(int limit, UUID maxId, UUID sinceId, UUID minId) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 40;

    public StatusPage {
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }
}
