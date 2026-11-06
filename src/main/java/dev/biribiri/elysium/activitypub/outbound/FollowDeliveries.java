package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Follow;
import dev.biribiri.elysium.activitypub.vocab.Undo;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowRequested;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.FollowWithdrawn;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Sends a local account's requests to follow actors on other servers, and withdraws them. Both are safe to run again, and
 * do nothing for what's since been removed.
 */
@Service
public class FollowDeliveries {

    private final ActorService actors;
    private final FollowService follows;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    FollowDeliveries(ActorService actors, FollowService follows, ActivityDeliveries deliveries, JsonMapper json) {
        this.actors = actors;
        this.follows = follows;
        this.deliveries = deliveries;
        this.json = json;
    }

    /**
     * Only sends a request that hasn't been withdrawn since, so that an {@code Undo} handled first isn't followed by the
     * {@code Follow} it was meant to cancel. Each time the account asks again is a new try, since a job from an earlier one
     * may have given up.
     */
    @ApplicationModuleListener
    public void on(FollowRequested event) {
        if (!follows.isRequestCurrent(event.followerId(), event.followedId(), event.activityUri())) {
            return;
        }
        var follower = actors.findAnyLocalById(event.followerId());
        var followed = actors.findById(event.followedId());
        if (follower.isEmpty() || followed.isEmpty() || follower.get().isDeleted()) {
            return;
        }
        var follow = Follow.of(event.activityUri(), follower.get().uri(), followed.get().uri());
        deliveries.enqueue(
                new OutboundActivity(follow.id(), follower.get().id(), json.writeValueAsString(follow), WhileSuspended.WAIT),
                List.of(followed.get().inboxUri()),
                event.requestedAt().toString());
    }

    /**
     * Cancels the {@code Follow} if it hasn't been sent yet, and sends the {@code Undo} after it in case it's being sent
     * right now. The {@code Undo} still goes if the {@code Follow} never did, which other servers ignore.
     */
    @ApplicationModuleListener
    public void on(FollowWithdrawn event) {
        var follower = actors.findAnyLocalById(event.followerId());
        var followed = actors.findById(event.followedId());
        if (follower.isEmpty() || followed.isEmpty() || follower.get().isDeleted()) {
            return;
        }
        var undo = Undo.of(Follow.of(event.activityUri(), follower.get().uri(), followed.get().uri()));
        deliveries.cancel(event.activityUri());
        deliveries.enqueueAfter(
                new OutboundActivity(undo.id(), follower.get().id(), json.writeValueAsString(undo), WhileSuspended.WAIT),
                List.of(followed.get().inboxUri()),
                event.activityUri());
    }
}
