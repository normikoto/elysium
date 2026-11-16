package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * The latest fetch of a status's thread from other servers, through {@link RemoteThreadFetcher}.
 *
 * @param statusId    the status whose thread it fills in
 * @param running     whether it can still store more
 * @param resultCount how many statuses it's stored so far
 */
public record ThreadRefresh(UUID statusId, boolean running, int resultCount) {
}
