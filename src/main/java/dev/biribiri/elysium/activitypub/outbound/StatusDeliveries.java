package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.vocab.Create;
import dev.biribiri.elysium.activitypub.vocab.Delete;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusDeleted;
import dev.biribiri.elysium.content.StatusPublished;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sends new and deleted statuses to the author's followers on other servers. This can run again for the same status, which
 * only sends it again if its jobs have finished, and other servers ignore an {@code id} they've already seen.
 */
@Service
public class StatusDeliveries {

    private final StatusService statuses;
    private final ActorService actors;
    private final FollowService follows;
    private final Notes notes;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    StatusDeliveries(
            StatusService statuses,
            ActorService actors,
            FollowService follows,
            Notes notes,
            ActivityDeliveries deliveries,
            JsonMapper json) {
        this.statuses = statuses;
        this.actors = actors;
        this.follows = follows;
        this.notes = notes;
        this.deliveries = deliveries;
        this.json = json;
    }

    /**
     * What's gone by now has nothing left to send. Failing for that would only make this be tried again forever.
     *
     * <p>Holding the status until this commits means that it's either already deleted here, or that deleting it waits, and
     * then finds the {@code Create} to cancel.
     */
    @ApplicationModuleListener
    public void on(StatusPublished event) {
        var status = statuses.findAndHold(event.statusId(), event.authorId())
                // Direct statuses have no audience until there are mentions.
                .filter(found -> found.visibility() != Visibility.DIRECT);
        var author = actors.findLocalById(event.authorId());
        if (status.isEmpty() || author.isEmpty()) {
            return;
        }
        var inboxes = inboxes(author.get().id(), status.get().visibility(), status.get().publishedAt());
        if (inboxes.isEmpty()) {
            return;
        }
        var create = Create.of(notes.of(status.get(), author.get()));
        deliveries.enqueue(
                new OutboundActivity(create.id().toString(), author.get().id(), json.writeValueAsString(create)), inboxes);
    }

    /**
     * Stops any further sending of the status, and tells followers to remove it, after any {@code Create} still being sent.
     * Who was sent the status isn't kept, so this goes to current followers, as {@link #inboxes} decides.
     */
    @ApplicationModuleListener
    public void on(StatusDeleted event) {
        if (event.visibility() == Visibility.DIRECT) {
            return;
        }
        var create = Create.idFor(event.statusUri());
        deliveries.cancel(create);
        var author = actors.findLocalById(event.authorId());
        if (author.isEmpty()) {
            return;
        }
        var inboxes = inboxes(author.get().id(), event.visibility(), event.publishedAt());
        if (inboxes.isEmpty()) {
            return;
        }
        var delete = Delete.of(event.statusUri(), author.get(), event.visibility());
        deliveries.enqueueAfter(
                new OutboundActivity(delete.id().toString(), author.get().id(), json.writeValueAsString(delete)),
                inboxes,
                create);
    }

    /**
     * Where to send a status's activities. Someone who follows after a followers-only status was posted gets neither its
     * {@code Create} nor its {@code Delete}, so that its {@code Delete} can't tell them it existed. Both have to use the same
     * rule, or a follower could be sent a status and not its deletion. Other statuses can be fetched, so a later follower may
     * have a copy to remove.
     */
    private List<URI> inboxes(UUID authorId, Visibility visibility, Instant publishedAt) {
        return visibility == Visibility.PRIVATE
                ? follows.followerInboxesAsOf(authorId, publishedAt)
                : follows.followerInboxes(authorId);
    }
}
