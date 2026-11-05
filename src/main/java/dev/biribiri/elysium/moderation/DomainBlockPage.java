package dev.biribiri.elysium.moderation;

import java.util.UUID;

/**
 * Selects one page of the list of blocks. Results are always returned newest first.
 *
 * @param limit   the page size, clamped to 1 to {@value MAX_LIMIT}
 * @param maxId   only return blocks older than this, or null
 * @param sinceId only return blocks newer than this, starting from the newest, or null
 * @param minId   only return blocks newer than this, starting from the closest to it, or null. Takes priority over
 *                {@code sinceId}.
 */
public record DomainBlockPage(int limit, UUID maxId, UUID sinceId, UUID minId) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;

    public DomainBlockPage {
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }
}
