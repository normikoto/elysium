package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A status was deleted. Its row is gone by the time a listener runs, so unlike other events, this also carries the URI,
 * visibility, time of publishing, and parent's author that can't be looked up any more. It has none of the status's text,
 * because events are stored.
 *
 * @param inReplyToAccountId the parent's author, if the parent was still there when this was deleted and anyone could see
 *                           it, which is when its author was sent the reply. Otherwise null, as it is for an event stored
 *                           before this was added
 * @param mentionedIds       every account it mentioned, who were sent it. Null for an event stored before this was added,
 *                           when statuses didn't mention anyone
 * @param boosterIds         the local accounts that had boosted it, whose followers were sent it too. Null for an event
 *                           stored before boosts existed
 */
public record StatusDeleted(
        UUID statusId, URI statusUri, UUID authorId, Visibility visibility, Instant publishedAt, UUID inReplyToAccountId,
        List<UUID> mentionedIds, List<UUID> boosterIds) {

    /**
     * @return who it mentioned, which is nobody for an event stored before mentions existed
     */
    public List<UUID> mentioned() {
        return mentionedIds == null ? List.of() : mentionedIds;
    }

    /**
     * @return the local accounts that had boosted it, which is nobody for an event stored before boosts existed
     */
    public List<UUID> boosters() {
        return boosterIds == null ? List.of() : boosterIds;
    }
}
