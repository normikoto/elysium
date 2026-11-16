package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A local account unpinned a status. The pin is gone by the time a listener runs, so this carries what's needed to send the
 * {@code Remove} to whoever was sent the {@code Add}.
 *
 * @param activityUri the {@code Add}'s ID
 * @param localOnly   whether the status is local-only, so no {@code Add} was sent. False for an event stored before this
 *                    was added, when there were none
 */
public record StatusUnpinned(
        UUID actorId, URI statusUri, String activityUri, Visibility visibility, Instant publishedAt,
        boolean localOnly) {
}
