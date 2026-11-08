package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Optional;

/**
 * Stores boosts of local statuses. Only the {@code object}'s ID is read, since an embedded copy is the booster's word for
 * someone else's status. Boosts of statuses from other servers are ignored for now.
 */
@Component
class AnnounceHandler implements InboundActivityHandler {

    private final BoostService boosts;
    private final RemoteActors actors;
    private final NoteStorage storage;
    private final JsonMapper json;

    AnnounceHandler(BoostService boosts, RemoteActors actors, NoteStorage storage, JsonMapper json) {
        this.boosts = boosts;
        this.actors = actors;
        this.storage = storage;
        this.json = json;
    }

    @Override
    public String type() {
        return "Announce";
    }

    @Override
    public void handle(InboundActivity activity) {
        var document = json.readTree(activity.body());
        var target = ActivityObject.of(document).map(ActivityObject::id)
                .flatMap(ActivityObject::parseUri).flatMap(boosts::localTarget);
        if (target.isEmpty()) return;
        // Network requests must finish before receive opens its transaction.
        var booster = actors.fetchOrRefresh(URI.create(activity.actorUri()));
        // Read from the Announce alone, because its object's addressing is the boosted status's.
        var visibility = storage.visibility(Addressing.ofActivity(document), booster);
        if (visibility == Visibility.DIRECT) return;
        var published = Optional.ofNullable(RemoteNote.timestamp(document.path("published")))
                .orElse(activity.firstReceivedAt());
        boosts.receive(booster.id(), target.get().id(), URI.create(activity.id()), visibility, published,
                activity.firstReceivedAt());
    }
}
