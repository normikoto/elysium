package dev.biribiri.elysium.activitypub.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Optional;

/**
 * Removes a status or an actor that its server has deleted. The {@code object} can be an ID, or an embedded object, such as
 * a {@code Tombstone}, with an {@code id}. Nothing is fetched: the signature already shows that the actor's server sent this,
 * and that server speaks for everything on it, so the object only has to be there too.
 */
@Component
class DeleteHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(DeleteHandler.class);

    private final Deletions deletions;
    private final JsonMapper json;

    DeleteHandler(Deletions deletions, JsonMapper json) {
        this.deletions = deletions;
        this.json = json;
    }

    @Override
    public String type() {
        return "Delete";
    }

    @Override
    public void handle(InboundActivity activity) {
        var actor = URI.create(activity.actorUri());
        var object = objectOf(activity.body(), json);
        if (object.isEmpty() || !Inbox.isOnActorsServer(object.get(), actor)) {
            log.debug("Dropping Delete {}, whose object isn't one ID on its actor's server", activity.id());
            return;
        }
        deletions.apply(object.get(), actor);
    }

    /**
     * @return the ID of the deleted object, or empty if there isn't one that could be stored
     */
    static Optional<URI> objectOf(String body, JsonMapper json) {
        return ActivityObject.of(body, json)
                .map(ActivityObject::id)
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri);
    }
}
