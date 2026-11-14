package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusPinService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;

/**
 * Unpins a remote actor's status. It's remembered even when the status isn't stored, so that an {@code Add} that arrived
 * before it but runs after it can't pin the status.
 */
@Component
class RemoveHandler extends FeaturedChangeHandler {

    private final StatusPinService pins;

    RemoveHandler(RemoteActors remoteActors, ActorService actors, JsonMapper json, StatusPinService pins) {
        super(remoteActors, actors, json);
        this.pins = pins;
    }

    @Override
    public String type() {
        return "Remove";
    }

    @Override
    void apply(Actor actor, URI status, Instant at) {
        pins.receiveUnpin(actor.id(), status, at);
    }
}
