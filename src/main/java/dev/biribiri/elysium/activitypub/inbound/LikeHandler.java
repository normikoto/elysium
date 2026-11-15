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
    private final ReactionReceiver reactions;

    LikeHandler(LikeService likes, RemoteActors actors, JsonMapper json, ReactionReceiver reactions) {
        this.reactions = reactions;
        this.likes = likes;
        this.actors = actors;
        this.json = json;
    }

    @Override
    public String type() {
        return "Like";
    }

    /**
     * Misskey sends every reaction as a {@code Like} with its emoji, even its default one, so one with an emoji is a
     * reaction rather than a favourite, as Akkoma takes it.
     */
    @Override
    public void handle(InboundActivity activity) {
        var body = json.readTree(activity.body());
        if (reactions.receive(activity, body)) return;
        var target = ActivityObject.of(body).map(ActivityObject::id)
                .flatMap(ActivityObject::parseUri).flatMap(likes::localTarget);
        if (target.isEmpty() || likes.wasUndone(activity.id(), activity.actorUri(), target.get().id(),
                activity.firstReceivedAt())) return;
        // Network requests must finish before receive opens its transaction.
        var actor = actors.fetchOrRefresh(URI.create(activity.actorUri()));
        likes.receive(actor.id(), target.get().id(), activity.id(), activity.firstReceivedAt());
    }
}
