package dev.biribiri.elysium.content;

import java.util.Optional;
import java.util.UUID;

/**
 * Fills in a status's thread from other servers, for modules that can't depend on the federation code. The
 * {@code activitypub} module implements this.
 */
public interface RemoteThreadFetcher {

    /**
     * Fetches the parents that a status's thread is missing, and the replies to it that its server lists, in the
     * background, storing them as {@link StatusService#storeBackfilled} does. However often it's asked for, a status is
     * only fetched once in a while. This makes no request itself, so it's safe in a transaction.
     *
     * @return the fetch, if one is running, whether this started it or not
     */
    Optional<ThreadRefresh> fetchSoon(UUID statusId);

    /**
     * @return the latest fetch of the status's thread, or empty if there hasn't been one recently
     */
    Optional<ThreadRefresh> find(UUID statusId);
}
