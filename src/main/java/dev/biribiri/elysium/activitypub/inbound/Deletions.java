package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * What a {@code Delete} does, once something has shown that the actor's server means it: either a valid signature from the
 * actor, or the object's own server saying it's gone.
 */
@Component
class Deletions {

    private static final Logger log = LoggerFactory.getLogger(Deletions.class);

    private final ActorService actors;
    private final StatusService statuses;
    private final QuoteService quotes;

    Deletions(ActorService actors, StatusService statuses, QuoteService quotes) {
        this.actors = actors;
        this.statuses = statuses;
        this.quotes = quotes;
    }

    /**
     * An actor deleting itself loses everything stored about it, and its URI is refused from then on. An actor that isn't
     * stored is left alone: Mastodon sends these to every server it knows, and remembering them all would let any server
     * fill the database. Anything else is taken to be a status, which is removed if it's the actor's, and remembered either
     * way so that its {@code Create} can't store it later. A {@code QuoteAuthorization} that approved a quote stored here
     * withdraws the approval, and is remembered the same way, for a quote that arrives later still naming it.
     *
     * @param object the deleted object, already checked to be on the actor's server
     */
    void apply(URI object, URI actor) {
        if (object.equals(actor)) {
            actors.findByUri(actor).filter(found -> !actors.isLocal(found)).ifPresent(found -> actors.markRemoteDeleted(found.id()));
            return;
        }
        if (quotes.revokeApproval(object, actor)) {
            log.debug("Revoked the quotes approved by {}", object);
        }
        statuses.deleteRemote(object, actor);
    }
}
