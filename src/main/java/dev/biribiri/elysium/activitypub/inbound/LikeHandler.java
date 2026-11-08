package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.LikeService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

@Component
class LikeHandler implements InboundActivityHandler {
    private final LikeService likes;
    private final RemoteActors actors;
    private final JsonMapper json;

    LikeHandler(LikeService likes, RemoteActors actors, JsonMapper json) {
        this.likes = likes;
        this.actors = actors;
        this.json = json;
    }

    @Override
    public String type() {
        return "Like";
    }

    @Override
    public void handle(InboundActivity activity) {
        var target = ActivityObject.of(activity.body(), json).map(ActivityObject::id)
                .flatMap(ActivityObject::parseUri).flatMap(likes::localTarget);
        if (target.isEmpty() || likes.wasUndone(activity.id(), activity.actorUri(), target.get().id(),
                activity.firstReceivedAt())) return;
        // Network requests must finish before receive opens its transaction.
        var actor = actors.fetchOrRefresh(URI.create(activity.actorUri()));
        likes.receive(actor.id(), target.get().id(), activity.id(), activity.firstReceivedAt());
    }
}
