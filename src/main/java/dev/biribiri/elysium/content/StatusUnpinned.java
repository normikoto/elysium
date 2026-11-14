package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A local account unpinned a status. The pin is gone by the time a listener runs, so this carries what's needed to send the
 * {@code Remove} to whoever was sent the {@code Add}.
 *
 * @param activityUri the {@code Add}'s ID
 */
public record StatusUnpinned(
        UUID actorId, URI statusUri, String activityUri, Visibility visibility, Instant publishedAt) {
}
