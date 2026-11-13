package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * Asks a poll's server for its counts, for modules that can't depend on the federation code. The {@code activitypub}
 * module implements this.
 */
public interface RemotePollRefresher {

    /**
     * Fetches a remote poll again in the background, once however often it's asked for, and stores its counts with
     * {@link PollService#refreshRemote}. This makes no request itself, so it's safe in a transaction.
     */
    void refreshSoon(UUID statusId);
}
