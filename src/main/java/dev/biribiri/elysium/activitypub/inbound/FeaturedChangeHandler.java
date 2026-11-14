package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * An {@code Add} or {@code Remove} whose {@code target} is the actor's {@code featured} collection, which is how Mastodon
 * and Misskey send a pin or an unpin. Any other {@code target} is ignored, and so is an {@code object} that isn't a status,
 * such as the {@code Hashtag} Mastodon sends for a featured tag.
 *
 * <p>Mastodon and Misskey send these without an {@code id}, so {@link Inbox} makes one from the body, and pinning a status
 * again sends the same one as the first time. A repeat is processed, and which of an {@code Add} and a {@code Remove} came
 * last is told by when each last arrived.
 */
abstract class FeaturedChangeHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(FeaturedChangeHandler.class);

    private final RemoteActors remoteActors;
    private final ActorService actors;
    private final JsonMapper json;

    FeaturedChangeHandler(RemoteActors remoteActors, ActorService actors, JsonMapper json) {
        this.remoteActors = remoteActors;
        this.actors = actors;
        this.json = json;
    }

    @Override
    public boolean processesRepeats() {
        return true;
    }

    @Override
    public boolean acceptsWithoutId() {
        return true;
    }

    @Override
    public void handle(InboundActivity activity) {
        var actorUri = URI.create(activity.actorUri());
        var document = json.readTree(activity.body());
        var target = Optional.ofNullable(ActivityJson.idOf(document.path("target"))).flatMap(ActivityObject::parseUri);
        var status = ActivityObject.of(document)
                .filter(object -> object.type() == null || RemoteNote.isStatusType(object.type()))
                .map(ActivityObject::id)
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                // Only the actor's server can say what's in its collection, and only its own statuses can be.
                .filter(id -> Inbox.isOnActorsServer(id, actorUri));
        if (target.isEmpty() || status.isEmpty()) {
            log.debug("Ignoring {} {}, which doesn't have a status and a target", type(), activity.id());
            return;
        }
        // Network requests must finish before a transaction opens. This also learns the actor's featured collection.
        var actor = remoteActors.fetchOrRefresh(actorUri);
        if (!actors.findFeaturedUri(actor.id()).equals(target)) {
            log.debug("Ignoring {} {}, whose target isn't the actor's featured collection", type(), activity.id());
            return;
        }
        apply(actor, status.get(), activity.receivedAt());
    }

    /**
     * @param status one of the actor's statuses, which doesn't have to be stored
     * @param at     when the activity last arrived
     */
    abstract void apply(Actor actor, URI status, Instant at);
}
