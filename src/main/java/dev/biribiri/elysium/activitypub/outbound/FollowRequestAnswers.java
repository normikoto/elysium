package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.vocab.Accept;
import dev.biribiri.elysium.activitypub.vocab.Reject;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowRequestAuthorized;
import dev.biribiri.elysium.graph.FollowRequestRejected;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Answers a request to follow a local account from another server, once the account has decided. Both are safe to run
 * again, and send nothing once either actor is gone. An {@code Accept} is only sent while the follow it answers stands, so
 * one handled after an {@code Undo} isn't sent, and it has the ID an inbound {@code Follow} would get, so a later
 * {@code Undo} can cancel it in the same way.
 */
@Service
public class FollowRequestAnswers {

    private final ActorService actors;
    private final FollowService follows;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    FollowRequestAnswers(ActorService actors, FollowService follows, ActivityDeliveries deliveries, JsonMapper json) {
        this.actors = actors;
        this.follows = follows;
        this.deliveries = deliveries;
        this.json = json;
    }

    @ApplicationModuleListener
    public void on(FollowRequestAuthorized event) {
        if (event.activityUri() == null
                || !follows.isAcceptedThrough(event.followerId(), event.followedId(), event.activityUri())) {
            return;
        }
        var followed = actors.findLocalById(event.followedId());
        var follower = actors.findById(event.followerId());
        if (followed.isEmpty() || follower.isEmpty() || actors.isLocal(follower.get())) {
            return;
        }
        var accept = Accept.of(followed.get().uri(), event.activityUri(), follower.get().uri());
        // By the follow, so that this running again doesn't send another, while an approval after a new request does.
        deliveries.enqueue(
                new OutboundActivity(accept.id().toString(), followed.get().id(), json.writeValueAsString(accept)),
                List.of(follower.get().inboxUri()),
                event.followId().toString());
    }

    @ApplicationModuleListener
    public void on(FollowRequestRejected event) {
        if (event.activityUri() == null) {
            return;
        }
        var followed = actors.findLocalById(event.followedId());
        var follower = actors.findById(event.followerId());
        if (followed.isEmpty() || follower.isEmpty() || actors.isLocal(follower.get())) {
            return;
        }
        var reject = Reject.of(followed.get().uri(), event.activityUri(), follower.get().uri());
        deliveries.enqueue(
                new OutboundActivity(reject.id().toString(), followed.get().id(), json.writeValueAsString(reject)),
                List.of(follower.get().inboxUri()));
    }
}
