package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A status was deleted. Its row is gone by the time a listener runs, so unlike other events, this also carries the URI,
 * visibility, time of publishing, and parent's author that can't be looked up any more. It has none of the status's text,
 * because events are stored.
 *
 * @param inReplyToAccountId the parent's author, or null if it wasn't a reply, or the event was stored before this was added
 */
public record StatusDeleted(
        UUID statusId, URI statusUri, UUID authorId, Visibility visibility, Instant publishedAt, UUID inReplyToAccountId) {
}
