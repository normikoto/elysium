package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.vocab.Accept;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;

/**
 * Accepts every request to follow a local account. The instance actor can't be followed: it isn't in the table that follows
 * refer to, and activities can't be sent as it.
 */
@Component
class FollowHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(FollowHandler.class);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final FollowService follows;
    private final ActivityDeliveries deliveries;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    FollowHandler(
            ActorService actors,
            RemoteActors remoteActors,
            FollowService follows,
            ActivityDeliveries deliveries,
            TransactionTemplate transaction,
            JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.follows = follows;
        this.deliveries = deliveries;
        this.transaction = transaction;
        this.json = json;
    }

    @Override
    public String type() {
        return "Follow";
    }

    @Override
    public void handle(InboundActivity activity) {
        var followed = ActivityObject.of(activity.body(), json)
                .map(ActivityObject::id)
                .flatMap(ActivityObject::parseUri)
                .flatMap(actors::findLocalByUri);
        if (followed.isEmpty()) {
            log.debug("Ignoring Follow {}, which isn't of a local account", activity.id());
            return;
        }
        if (follows.wasUndone(activity.id(), activity.actorUri(), followed.get().id(), activity.arrival())) {
            return;
        }
        // Before the transaction, because this can make a request to another server.
        var follower = remoteActors.fetchOrRefresh(URI.create(activity.actorUri()));
        accept(activity, follower, followed.get());
    }

    /**
     * A repeat is a retry, or a server that's lost track of this one and is asking again, so it can get another answer. It's
     * still stopped if the {@code Follow} was undone, because a new request has a new ID.
     */
    @Override
    public boolean processesRepeats() {
        return true;
    }

    /**
     * An {@code Accept} is sent even if the follow already exists, because the other server may have lost its copy and be
     * asking again. One for a {@code Follow} that this replaces isn't needed any more, which leaves one to cancel at most if
     * the follow is undone.
     */
    private void accept(InboundActivity activity, Actor follower, Actor followed) {
        var accept = Accept.of(followed.uri(), activity.id(), follower.uri());
        var body = json.writeValueAsString(accept);
        transaction.executeWithoutResult(status -> {
            var accepted = follows.accept(follower.id(), follower.uri(), followed.id(), activity.id(), activity.arrival());
            if (accepted.isEmpty()) {
                return;
            }
            accepted.get().replacedActivity()
                    .ifPresent(replaced -> deliveries.cancel(Accept.idFor(followed.uri(), replaced)));
            // By arrival, so that a repeat gets an answer even if the answer to the last one failed and was set aside, while
            // this job running again doesn't send another.
            deliveries.enqueue(
                    new OutboundActivity(accept.id().toString(), followed.id(), body),
                    List.of(follower.inboxUri()),
                    activity.receivedAt().toString());
        });
    }
}
