package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Optional;

/**
 * Works out which follow an {@code Accept} or {@code Reject} answers. Only the actor being followed can answer, and that's
 * the actor that signed it. Most servers name the {@code Follow} by its ID, which is enough, because each request from here
 * has a new one, so an answer to an earlier request can't be taken for one to a newer request. Some embed the
 * {@code Follow} without its ID, so all that's left to go on is the two actors.
 */
@Component
class FollowAnswers {

    private static final Logger log = LoggerFactory.getLogger(FollowAnswers.class);

    private final ActorService actors;
    private final JsonMapper json;

    FollowAnswers(ActorService actors, JsonMapper json) {
        this.actors = actors;
        this.json = json;
    }

    /**
     * @return which follow the activity answers, or empty if it isn't an answer to a {@code Follow} from here
     */
    Optional<Answered> read(InboundActivity activity) {
        var object = ActivityObject.of(activity.body(), json);
        if (object.isEmpty() || (object.get().type() != null && !object.get().type().equals("Follow"))) {
            log.debug("Ignoring {} {}, which isn't of a Follow", activity.type(), activity.id());
            return Optional.empty();
        }
        var followed = actors.findByUri(URI.create(activity.actorUri())).filter(found -> !actors.isLocal(found));
        if (followed.isEmpty()) {
            // Nothing can be following an actor that isn't stored.
            return Optional.empty();
        }
        var follow = object.get();
        if (follow.id() != null) {
            return Optional.of(new Answered(followed.get(), follow.id(), null));
        }
        // An embedded Follow of someone else can't be this actor's to answer.
        if (follow.object() != null && !follow.object().equals(activity.actorUri())) {
            return Optional.empty();
        }
        return Optional.ofNullable(follow.actor())
                .flatMap(ActivityObject::parseUri)
                .flatMap(actors::findAnyLocalByUri)
                .map(follower -> new Answered(followed.get(), null, follower));
    }

    /**
     * @param followed     the remote actor that answered
     * @param followId     the {@code Follow} it answered, if it said
     * @param follower     the local account whose follow it answered, if it didn't say which {@code Follow}
     */
    record Answered(Actor followed, String followId, Actor follower) {
    }
}
