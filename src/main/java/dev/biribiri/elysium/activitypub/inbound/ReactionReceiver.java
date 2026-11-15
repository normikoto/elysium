package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.ReactionService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * Stores a reaction to a local status, for {@link EmojiReactHandler} and for a {@code Like} that has an emoji.
 */
@Component
class ReactionReceiver {

    private final ReactionService reactions;
    private final LikeService likes;
    private final RemoteActors actors;
    private final JsonMapper json;

    ReactionReceiver(ReactionService reactions, LikeService likes, RemoteActors actors, JsonMapper json) {
        this.reactions = reactions;
        this.likes = likes;
        this.actors = actors;
        this.json = json;
    }

    /**
     * @return false if the activity has no emoji, so it isn't a reaction
     */
    boolean receive(InboundActivity activity) {
        var body = json.readTree(activity.body());
        var reaction = ReactionContent.read(body);
        if (reaction.isEmpty()) {
            return false;
        }
        // Only local statuses, which are never fetched, as for likes.
        var target = ActivityObject.of(body).map(ActivityObject::id)
                .flatMap(ActivityObject::parseUri).flatMap(likes::localTarget);
        if (target.isEmpty() || reactions.wasUndone(activity.id(), activity.actorUri())) {
            return true;
        }
        // Network requests have to finish before receive opens its transaction.
        var actor = actors.fetchOrRefresh(URI.create(activity.actorUri()));
        reactions.receive(actor.id(), target.get().id(), reaction.get().name(), reaction.get().emoji(), activity.id(),
                activity.firstReceivedAt());
        return true;
    }
}
