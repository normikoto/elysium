package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Handles every {@code Undo}, because only one handler can have a type. Only undoing a {@code Follow} does anything so far.
 * Other types can be added here by looking at what the {@code object} is.
 */
@Component
class UndoHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(UndoHandler.class);

    private final ActorService actors;
    private final FollowService follows;
    private final JsonMapper json;

    UndoHandler(ActorService actors, FollowService follows, JsonMapper json) {
        this.actors = actors;
        this.follows = follows;
        this.json = json;
    }

    @Override
    public String type() {
        return "Undo";
    }

    @Override
    public void handle(InboundActivity activity) {
        var parsed = ActivityObject.of(activity.body(), json);
        if (parsed.isEmpty() || !parsed.get().couldBeFrom(activity.actorUri())) {
            log.debug("Ignoring Undo {}, which doesn't undo something of its actor's", activity.id());
            return;
        }
        var object = parsed.get();
        if (object.type() != null && !object.type().equals("Follow")) {
            return;
        }
        if (object.id() != null) {
            undoById(activity.actorUri(), object.id());
        } else if (object.object() != null && "Follow".equals(object.type())) {
            undoByPair(activity.actorUri(), object.object());
        }
    }

    /**
     * A server that uses the same ID for each {@code Follow} can do the same for each {@code Undo}, and the follow it
     * undoes can be a new one.
     */
    @Override
    public boolean onRepeat(InboundActivity activity) {
        return true;
    }

    /**
     * When an activity is sent as just an ID, it could be of any type. Remembering one that isn't a {@code Follow} has no
     * effect, and it's cleaned up with the rest.
     *
     * <p>An ID in an {@code Undo} hasn't been through the checks that an inbox makes, so they're made here. Without them, a
     * server could stop another server's {@code Follow} by undoing its ID first, and an ID too long to store would fail
     * every time this job is tried.
     */
    private void undoById(String actorUri, String followId) {
        if (!InboundActivity.isStorableId(followId) || !canSpeakFor(actorUri, followId)) {
            log.debug("Ignoring an Undo from {} of an activity it can't speak for", actorUri);
            return;
        }
        var follower = actors.findByUri(URI.create(actorUri));
        if (follower.isPresent()) {
            follows.undo(follower.get().id(), followId);
        } else {
            follows.undo(followId);
        }
    }

    private void undoByPair(String actorUri, String followedUri) {
        URI followedId;
        try {
            followedId = new URI(followedUri);
        } catch (URISyntaxException e) {
            return;
        }
        var follower = actors.findByUri(URI.create(actorUri));
        var followed = actors.findLocalByUri(followedId);
        if (follower.isPresent() && followed.isPresent()) {
            follows.removeByPair(follower.get().id(), followed.get().id());
        }
    }

    private static boolean canSpeakFor(String actorUri, String id) {
        try {
            return Inbox.isOnActorsServer(new URI(id), URI.create(actorUri));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
