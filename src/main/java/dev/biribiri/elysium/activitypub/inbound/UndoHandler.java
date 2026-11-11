package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.vocab.Accept;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.LikeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Handles every {@code Undo}, because only one handler can have a type, and tells what's undone from the {@code object}'s
 * type, or the stored activity's when it's sent as just an ID. Undoing a {@code Follow}, {@code Like}, {@code Announce} or
 * {@code Block} does something.
 */
@Component
class UndoHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(UndoHandler.class);

    private final ActorService actors;
    private final FollowService follows;
    private final LikeService likes;
    private final BoostService boosts;
    private final BlockService blocks;
    private final StoredActivities stored;
    private final ActivityDeliveries deliveries;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    UndoHandler(
            ActorService actors,
            FollowService follows,
            LikeService likes,
            BoostService boosts,
            BlockService blocks,
            StoredActivities stored,
            ActivityDeliveries deliveries,
            TransactionTemplate transaction,
            JsonMapper json) {
        this.actors = actors;
        this.follows = follows;
        this.likes = likes;
        this.boosts = boosts;
        this.blocks = blocks;
        this.stored = stored;
        this.deliveries = deliveries;
        this.transaction = transaction;
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
        if (object.type() != null && !object.type().equals("Follow") && !object.type().equals("Like")
                && !object.type().equals("Announce") && !object.type().equals("Block")) return;
        var original = Optional.ofNullable(object.id()).flatMap(stored::find);
        if (original.isPresent() && (!original.get().actorUri().equals(activity.actorUri())
                || (object.type() != null && !object.type().equals(original.get().type())))) return;
        var type = object.type() != null ? object.type() : original.map(InboundActivity::type).orElse(null);
        if ("Like".equals(type) || type == null) {
            undoLike(activity, object, original);
            if ("Like".equals(type)) return;
        }
        if ("Announce".equals(type) || type == null) {
            undoAnnounce(activity, object);
            if ("Announce".equals(type)) return;
        }
        if ("Block".equals(type) || type == null) {
            undoBlock(activity, object, original);
            if ("Block".equals(type)) return;
        }
        if (type != null && !type.equals("Follow")) return;
        if (object.id() != null) {
            undoById(activity, object);
        } else if (object.object() != null && "Follow".equals(object.type())) {
            undoByPair(activity, object.object());
        }
    }

    private void undoLike(InboundActivity activity, ActivityObject object, Optional<InboundActivity> original) {
        if (object.id() != null && (!InboundActivity.isStorableId(object.id())
                || !canSpeakFor(activity.actorUri(), object.id()))) return;
        var target = original.filter(found -> found.type().equals("Like"))
                .flatMap(found -> ActivityObject.of(found.body(), json)).map(ActivityObject::id)
                .or(() -> Optional.ofNullable(object.object()))
                .flatMap(ActivityObject::parseUri).flatMap(likes::localStatusId);
        if (object.id() == null && (!"Like".equals(object.type()) || target.isEmpty())) return;
        likes.undo(activity.actorUri(), object.id(), target,
                original.filter(found -> found.type().equals("Like")).map(InboundActivity::firstReceivedAt),
                activity.receivedAt());
    }

    /**
     * Like a {@code Like}, a {@code Block} is undone by its ID, or by its two actors if it's embedded without one. Its target
     * comes from the stored {@code Block} when there's one, since the embedded copy hasn't been through the inbox's checks.
     */
    private void undoBlock(InboundActivity activity, ActivityObject object, Optional<InboundActivity> original) {
        if (object.id() != null && (!InboundActivity.isStorableId(object.id())
                || !canSpeakFor(activity.actorUri(), object.id()))) return;
        var block = original.filter(found -> found.type().equals("Block"));
        var target = block.flatMap(found -> ActivityObject.of(found.body(), json)).map(ActivityObject::id)
                .or(() -> Optional.ofNullable(object.object()))
                .flatMap(ActivityObject::parseUri).flatMap(actors::findAnyLocalByUri);
        if (object.id() == null && (!"Block".equals(object.type()) || target.isEmpty())) return;
        blocks.undo(activity.actorUri(), object.id(), target, block.map(InboundActivity::firstReceivedAt),
                activity.receivedAt());
    }

    /**
     * A boost is stored under its {@code Announce}'s ID, so that's all an {@code Undo} needs, and one without an ID can't
     * name a boost. The {@code Undo} is remembered even if nothing was boosted, since its {@code Announce} can arrive later.
     */
    private void undoAnnounce(InboundActivity activity, ActivityObject object) {
        if (object.id() == null || !InboundActivity.isStorableId(object.id())
                || !canSpeakFor(activity.actorUri(), object.id())) return;
        ActivityObject.parseUri(object.id()).ifPresent(announce ->
                boosts.undo(URI.create(activity.actorUri()), announce));
    }

    /**
     * Local actors are looked up in any state, because someone can stop following a suspended account.
     *
     * <p>Misskey uses the same ID for each {@code Undo} between two actors, with a new ID for each {@code Follow}, so a repeat
     * can undo a new follow. The {@code Follow} it embeds has an ID that's never sent, so it matches on the two actors.
     */
    @Override
    public boolean processesRepeats() {
        return true;
    }

    /**
     * When an activity is sent as just an ID, it could be of any type. Remembering one that isn't a {@code Follow} has no
     * effect, and it's cleaned up with the rest.
     *
     * <p>An ID in an {@code Undo} hasn't been through the checks that an inbox makes, so they're made here. A {@code Follow}
     * that was accepted always has an ID on its actor's server, and an ID too long to store would fail every time this job
     * is tried.
     */
    private void undoById(InboundActivity activity, ActivityObject object) {
        var actorUri = activity.actorUri();
        var followId = object.id();
        if (!InboundActivity.isStorableId(followId) || !canSpeakFor(actorUri, followId)) {
            log.debug("Ignoring an Undo from {} of an activity it can't speak for", actorUri);
            return;
        }
        // Another actor on the same server can use this ID, which doesn't make it that actor's activity.
        var follow = stored.find(followId).filter(found -> found.actorUri().equals(actorUri) && found.type().equals("Follow"));
        var followedUri = follow.flatMap(found -> ActivityObject.of(found.body(), json))
                .map(ActivityObject::id)
                .or(() -> Optional.ofNullable(object.object()));
        var followed = followedUri.flatMap(ActivityObject::parseUri).flatMap(actors::findAnyLocalByUri).map(Actor::id);
        transaction.executeWithoutResult(status ->
                follows.undo(actorUri, followed, followId, follow.map(InboundActivity::arrival), activity.receivedAt())
                        .ifPresent(this::cancelAccept));
    }

    private void undoByPair(InboundActivity activity, String followedUri) {
        ActivityObject.parseUri(followedUri).flatMap(actors::findAnyLocalByUri).ifPresent(followed ->
                transaction.executeWithoutResult(status ->
                        follows.undoPair(activity.actorUri(), followed.id(), activity.receivedAt())
                                .ifPresent(this::cancelAccept)));
    }

    /**
     * The other server has withdrawn the request, so it shouldn't get an answer if one is still waiting to be sent.
     */
    private void cancelAccept(FollowService.Removed removed) {
        actors.findAnyLocalById(removed.followedId()).ifPresent(followed ->
                deliveries.cancel(Accept.idFor(followed.uri(), removed.activityUri())));
    }

    private static boolean canSpeakFor(String actorUri, String id) {
        try {
            return Inbox.isOnActorsServer(new URI(id), URI.create(actorUri));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
