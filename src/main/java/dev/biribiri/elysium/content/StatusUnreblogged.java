package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A local account's boost was removed, by unboosting it or because the status it boosted was deleted. Its row is gone by
 * the time a listener runs, so this carries what's needed to withdraw its {@code Announce} from whoever was sent it.
 *
 * @param boostUri  the {@code Announce}'s ID
 * @param statusUri the status it boosted
 * @param authorId  that status's author
 * @param localOnly whether that status is local-only, so its boost was never sent. False for an event stored before this
 *                  was added, when there were none
 */
public record StatusUnreblogged(
        UUID boostId, URI boostUri, UUID actorId, UUID statusId, URI statusUri, UUID authorId, Visibility visibility,
        Instant publishedAt, boolean localOnly) {

    static StatusUnreblogged of(StatusBoost boost, URI statusUri, UUID authorId) {
        return new StatusUnreblogged(boost.id(), boost.uri(), boost.actorId(), boost.statusId(), statusUri, authorId,
                boost.visibility(), boost.publishedAt(), boost.localOnly());
    }
}
