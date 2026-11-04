package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A status was deleted. Its row is gone by the time a listener runs, so unlike other events, this also carries the URI,
 * visibility, and time of publishing that can't be looked up any more. It has none of the status's text, because events are
 * stored.
 */
public record StatusDeleted(UUID statusId, URI statusUri, UUID authorId, Visibility visibility, Instant publishedAt) {
}
