package dev.biribiri.elysium.graph;

import java.util.UUID;

/**
 * A page of a local account's server blocks, newest first. The cursors are from {@link AccountDomainBlockService.Entry#id}.
 *
 * @param maxId   only blocks older than this one
 * @param sinceId only blocks newer than this one, newest first
 * @param minId   only blocks newer than this one, starting from the oldest of them, but still listed newest first
 */
public record DomainBlockListPage(int limit, UUID maxId, UUID sinceId, UUID minId) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;

    public DomainBlockListPage {
        limit = Math.clamp(limit, 1, MAX_LIMIT);
    }
}
