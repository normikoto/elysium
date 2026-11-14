package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;

/**
 * Pins a remote actor's status. One that isn't stored is fetched first, in a job that takes its turn.
 */
@Component
class AddHandler extends FeaturedChangeHandler {

    private final StatusService statuses;
    private final StatusPinService pins;
    private final FeaturedFetch featured;

    AddHandler(RemoteActors remoteActors, ActorService actors, JsonMapper json, StatusService statuses,
               StatusPinService pins, FeaturedFetch featured) {
        super(remoteActors, actors, json);
        this.statuses = statuses;
        this.pins = pins;
        this.featured = featured;
    }

    @Override
    public String type() {
        return "Add";
    }

    @Override
    void apply(Actor actor, URI status, Instant at) {
        statuses.findStoredByUri(status, actor.id()).ifPresentOrElse(
                stored -> pins.receivePin(actor.id(), stored, at),
                () -> featured.queueNote(actor.id(), status, at));
    }
}
