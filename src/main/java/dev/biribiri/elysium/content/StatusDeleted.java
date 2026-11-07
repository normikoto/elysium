package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A status was deleted. Its row is gone by the time a listener runs, so unlike other events, this also carries the URI,
 * visibility, time of publishing, and parent's author that can't be looked up any more. It has none of the status's text,
 * because events are stored.
 *
 * @param inReplyToAccountId the parent's author, if the parent was still there when this was deleted and anyone could see
 *                           it, which is when its author was sent the reply. Otherwise null, as it is for an event stored
 *                           before this was added
 */
public record StatusDeleted(
        UUID statusId, URI statusUri, UUID authorId, Visibility visibility, Instant publishedAt, UUID inReplyToAccountId) {
}
