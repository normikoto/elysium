package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.CollectionChange;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusPinned;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusUnpinned;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends local pins to the author's followers as {@code Add}s to their {@code featured} collection, as Mastodon does, and
 * unpins as {@code Remove}s. Either can run again, since pinning is a set.
 */
@Service
public class PinDeliveries {

    private final StatusPinService pins;
    private final StatusService statuses;
    private final ActorService actors;
    private final Recipients recipients;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    PinDeliveries(StatusPinService pins, StatusService statuses, ActorService actors, Recipients recipients,
                  ActivityDeliveries deliveries, JsonMapper json) {
        this.pins = pins;
        this.statuses = statuses;
        this.actors = actors;
        this.recipients = recipients;
        this.deliveries = deliveries;
        this.json = json;
    }

    /**
     * Holding the pin until this commits means an unpin either finds the {@code Add} to cancel, or has already removed the
     * pin, so that its {@code Remove} can't be sent first.
     */
    @ApplicationModuleListener
    public void on(StatusPinned event) {
        var actor = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        if (actor.isEmpty()) return;
        pins.findAndHold(event.pinId()).ifPresent(pin ->
                statuses.findVisible(pin.statusId(), Optional.of(pin.actorId())).ifPresent(status -> {
                    var inboxes = inboxes(actor.get().id(), status.visibility(), status.publishedAt());
                    if (inboxes.isEmpty()) return;
                    var add = CollectionChange.pin(pin.activityUri(), actor.get(), status.uri(), status.visibility());
                    deliveries.enqueue(new OutboundActivity(add.id(), actor.get().id(), json.writeValueAsString(add),
                            WhileSuspended.WAIT), inboxes);
                }));
    }

    /**
     * Sends the {@code Remove} after any {@code Add} still being sent, since a server that got them the other way round
     * would keep the status pinned.
     */
    @ApplicationModuleListener
    public void on(StatusUnpinned event) {
        deliveries.cancel(event.activityUri());
        var actor = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        if (actor.isEmpty()) return;
        var inboxes = inboxes(actor.get().id(), event.visibility(), event.publishedAt());
        if (inboxes.isEmpty()) return;
        var remove = CollectionChange.unpin(CollectionChange.removalIdFor(event.activityUri()), actor.get(),
                event.statusUri(), event.visibility());
        deliveries.enqueueAfter(new OutboundActivity(remove.id(), actor.get().id(), json.writeValueAsString(remove),
                WhileSuspended.WAIT), inboxes, event.activityUri());
    }

    /**
     * The followers who were sent the status, so that a pin doesn't tell anyone else a followers-only status exists.
     */
    private List<URI> inboxes(UUID actorId, Visibility visibility, Instant publishedAt) {
        var inboxes = new LinkedHashSet<URI>();
        recipients.addFollowers(actorId, visibility, publishedAt, inboxes);
        return List.copyOf(inboxes);
    }
}
