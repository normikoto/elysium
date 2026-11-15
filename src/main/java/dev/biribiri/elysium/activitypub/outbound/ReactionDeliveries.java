package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Emoji;
import dev.biribiri.elysium.activitypub.vocab.EmojiReact;
import dev.biribiri.elysium.activitypub.vocab.Undo;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.ReactionService;
import dev.biribiri.elysium.content.StatusReacted;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusUnreacted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends local reactions to other servers' statuses, and their withdrawal, as {@link LikeDeliveries} sends likes. Both are
 * safe to repeat, and send nothing for a reaction or status that's gone.
 */
@Service
public class ReactionDeliveries {

    private final ReactionService reactions;
    private final StatusService statuses;
    private final ActorService actors;
    private final CustomEmojis emojis;
    private final InstanceProperties instance;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    ReactionDeliveries(ReactionService reactions, StatusService statuses, ActorService actors, CustomEmojis emojis,
                       InstanceProperties instance, ActivityDeliveries deliveries, JsonMapper json) {
        this.reactions = reactions;
        this.statuses = statuses;
        this.actors = actors;
        this.emojis = emojis;
        this.instance = instance;
        this.deliveries = deliveries;
        this.json = json;
    }

    @ApplicationModuleListener
    public void on(StatusReacted event) {
        var actor = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        var author = actors.findById(event.authorId()).filter(Actor::isActive).filter(found -> !actors.isLocal(found));
        if (actor.isEmpty() || author.isEmpty()) return;
        // Held through enqueuing, so that a withdrawal can't be followed by a late listener's reaction.
        reactions.findAndHold(event.reactionId()).ifPresent(saved ->
                statuses.findVisible(saved.statusId(), Optional.of(saved.actorId())).ifPresent(status -> {
                    var react = react(saved.activityUri(), actor.get().uri(), status.uri(), author.get().uri(),
                            saved.name(), saved.emojiId());
                    deliveries.enqueue(new OutboundActivity(react.id(), actor.get().id(), json.writeValueAsString(react),
                            WhileSuspended.WAIT), List.of(author.get().inboxUri()));
                }));
    }

    @ApplicationModuleListener
    public void on(StatusUnreacted event) {
        var actor = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        var author = actors.findById(event.authorId()).filter(Actor::isActive).filter(found -> !actors.isLocal(found));
        if (actor.isEmpty() || author.isEmpty()) return;
        var undo = Undo.of(react(event.activityUri(), actor.get().uri(), event.statusUri(), author.get().uri(),
                event.name(), event.emojiId()));
        deliveries.cancel(event.activityUri());
        deliveries.enqueueAfter(new OutboundActivity(undo.id(), actor.get().id(), json.writeValueAsString(undo),
                WhileSuspended.WAIT), List.of(author.get().inboxUri()), event.activityUri());
    }

    /**
     * A custom emoji that's been removed since is still named, without its image.
     */
    private EmojiReact react(String id, URI actor, URI status, URI author, String name, UUID emojiId) {
        if (emojiId == null) {
            return EmojiReact.of(id, actor, status, author, name, null);
        }
        var emoji = emojis.find(emojiId).map(found -> Emoji.of(found, instance)).orElse(null);
        return EmojiReact.of(id, actor, status, author, ":" + name + ":", emoji);
    }
}
