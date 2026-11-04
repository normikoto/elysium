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
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

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
                .flatMap(FollowHandler::parseUri)
                .flatMap(actors::findLocalByUri);
        if (followed.isEmpty()) {
            log.debug("Ignoring Follow {}, which isn't of a local account", activity.id());
            return;
        }
        if (follows.wasUndone(activity.id())) {
            return;
        }
        // Before the transaction, because this can make a request to another server.
        var follower = remoteActors.fetchOrRefresh(URI.create(activity.actorUri()));
        accept(activity.id(), follower, followed.get());
    }

    /**
     * A repeat that arrives after an {@code Undo} is a new request to follow, from a server that uses the same ID each time.
     * That's different from this job running again, which isn't a new request, and which the {@code Undo} still stops.
     * A repeat with no {@code Undo} means that the other server is asking again, so it gets another {@code Accept}.
     */
    @Override
    public boolean onRepeat(InboundActivity activity) {
        follows.forgetUndone(activity.id());
        return true;
    }

    /**
     * An {@code Accept} is sent even if the follow already exists, because the other server may have lost its copy and be
     * asking again.
     */
    private void accept(String followId, Actor follower, Actor followed) {
        var accept = Accept.of(followed.uri(), followId, follower.uri());
        var body = json.writeValueAsString(accept);
        transaction.executeWithoutResult(status -> {
            if (follows.accept(follower.id(), followed.id(), followId)) {
                deliveries.enqueue(
                        new OutboundActivity(accept.id().toString(), followed.id(), body),
                        List.of(follower.inboxUri()));
            }
        });
    }

    private static Optional<URI> parseUri(String value) {
        try {
            return Optional.of(new URI(value));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
