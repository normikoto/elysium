package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A successful local or remote insertion. Unlike StatusPublished, this must never trigger federation delivery.
 *
 * @param backfilled whether it's an older status fetched from its author's outbox, rather than one that was just posted or
 *                   sent here, so that nobody is told about it as if it were new
 */
public record StatusStored(UUID statusId, boolean backfilled) {

    public StatusStored(UUID statusId) {
        this(statusId, false);
    }
}
