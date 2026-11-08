package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A current boost, which is a status of its own.
 *
 * @param id         the boost's own status ID, which is its place in timelines
 * @param uri        its {@code Announce}'s ID
 * @param statusId   the status it boosts
 * @param activityAt when a remote boost's {@code Announce} first arrived, or null for a local one
 */
public record StatusBoost(
        UUID id, URI uri, UUID actorId, UUID statusId, Visibility visibility, Instant publishedAt, Instant activityAt) {
}
