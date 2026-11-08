package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Like;
import dev.biribiri.elysium.activitypub.vocab.Undo;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.StatusLiked;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusUnliked;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/** Durable, retry-safe delivery of local likes and their withdrawal. */
@Service
public class LikeDeliveries {
    private final LikeService likes;
    private final StatusService statuses;
    private final ActorService actors;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    LikeDeliveries(LikeService likes, StatusService statuses, ActorService actors,
                   ActivityDeliveries deliveries, JsonMapper json) {
        this.likes = likes;
        this.statuses = statuses;
        this.actors = actors;
        this.deliveries = deliveries;
        this.json = json;
    }

    @ApplicationModuleListener
    public void on(StatusLiked event) {
        var actor = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        var author = actors.findById(event.authorId()).filter(Actor::isActive).filter(found -> !actors.isLocal(found));
        if (actor.isEmpty() || author.isEmpty()) return;
        // This lock remains held through enqueue, so unfavourite can't be followed by a late listener's Like.
        likes.findAndHold(event.likeId()).ifPresent(saved ->
                statuses.findVisible(saved.statusId(), Optional.of(saved.actorId())).ifPresent(status -> {
                    var like = Like.of(saved.activityUri(), actor.get().uri(), status.uri(), author.get().uri());
                    deliveries.enqueue(new OutboundActivity(like.id(), actor.get().id(), json.writeValueAsString(like),
                            WhileSuspended.WAIT), List.of(author.get().inboxUri()));
                }));
    }

    @ApplicationModuleListener
    public void on(StatusUnliked event) {
        var actor = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        var author = actors.findById(event.authorId()).filter(Actor::isActive).filter(found -> !actors.isLocal(found));
        if (actor.isEmpty() || author.isEmpty()) return;
        var undo = Undo.of(Like.of(event.activityUri(), actor.get().uri(), event.statusUri(), author.get().uri()));
        deliveries.cancel(event.activityUri());
        deliveries.enqueueAfter(new OutboundActivity(undo.id(), actor.get().id(), json.writeValueAsString(undo),
                WhileSuspended.WAIT), List.of(author.get().inboxUri()), event.activityUri());
    }
}
