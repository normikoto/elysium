package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * What a {@code Delete} does, once something has shown that the actor's server means it: either a valid signature from the
 * actor, or the object's own server saying it's gone.
 */
@Component
class Deletions {

    private final ActorService actors;
    private final StatusService statuses;

    Deletions(ActorService actors, StatusService statuses) {
        this.actors = actors;
        this.statuses = statuses;
    }

    /**
     * An actor deleting itself loses everything stored about it, and its URI is refused from then on. An actor that isn't
     * stored is left alone: Mastodon sends these to every server it knows, and remembering them all would let any server
     * fill the database. Anything else is taken to be a status, which is removed if it's the actor's, and remembered either
     * way so that its {@code Create} can't store it later.
     *
     * @param object the deleted object, already checked to be on the actor's server
     */
    void apply(URI object, URI actor) {
        if (object.equals(actor)) {
            actors.findByUri(actor).filter(found -> !actors.isLocal(found)).ifPresent(found -> actors.markRemoteDeleted(found.id()));
            return;
        }
        statuses.deleteRemote(object, actor);
    }
}
