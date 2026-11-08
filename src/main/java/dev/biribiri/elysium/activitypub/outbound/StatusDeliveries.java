package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Create;
import dev.biribiri.elysium.activitypub.vocab.Delete;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusDeleted;
import dev.biribiri.elysium.content.StatusPublished;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Sends new and deleted statuses to the author's followers and the accounts they mention on other servers. This can run again for the same status, which
 * only sends it again if its jobs have finished, and other servers ignore an {@code id} they've already seen.
 */
@Service
public class StatusDeliveries {

    private final StatusService statuses;
    private final ActorService actors;
    private final Recipients recipients;
    private final Notes notes;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    StatusDeliveries(
            StatusService statuses,
            ActorService actors,
            Recipients recipients,
            Notes notes,
            ActivityDeliveries deliveries,
            JsonMapper json) {
        this.statuses = statuses;
        this.actors = actors;
        this.recipients = recipients;
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
        var status = statuses.findAndHold(event.statusId(), event.authorId());
        var author = actors.findLocalById(event.authorId());
        if (status.isEmpty() || author.isEmpty()) {
            return;
        }
        var mentioned = notes.mentioned(status.get());
        var note = notes.of(status.get(), author.get(), mentioned);
        var inboxes = inboxes(
                author.get().id(),
                status.get().visibility(),
                status.get().publishedAt(),
                note.inReplyTo() == null ? null : status.get().inReplyToAccountId(),
                mentioned);
        if (inboxes.isEmpty()) {
            return;
        }
        var create = Create.of(note);
        deliveries.enqueue(
                new OutboundActivity(
                        create.id().toString(), author.get().id(), json.writeValueAsString(create), WhileSuspended.DROP),
                inboxes);
    }

    /**
     * Stops any further sending of the status, and tells followers, the accounts it mentioned, and the followers of local
     * accounts that boosted it to remove it, after any {@code Create} still being sent. Which followers were sent the status isn't kept, so this goes to current followers, as
     * {@link #inboxes} decides. An author who's been
     * suspended since can come back, and this waits for that, so that followers don't keep what was deleted.
     */
    @ApplicationModuleListener
    public void on(StatusDeleted event) {
        var create = Create.idFor(event.statusUri());
        deliveries.cancel(create);
        var author = actors.findAnyLocalById(event.authorId()).filter(found -> !found.isDeleted());
        if (author.isEmpty()) {
            return;
        }
        var mentioned = actors.findByIds(event.mentioned());
        var inboxes = new LinkedHashSet<>(inboxes(
                author.get().id(), event.visibility(), event.publishedAt(), event.inReplyToAccountId(), mentioned));
        // Their followers can have it from their boosts, and the boosts go with it.
        event.boosters().forEach(booster ->
                recipients.addFollowers(booster, event.visibility(), event.publishedAt(), inboxes));
        if (inboxes.isEmpty()) {
            return;
        }
        var delete = Delete.of(
                event.statusUri(), author.get(), event.visibility(), mentioned.stream().map(Actor::uri).toList());
        deliveries.enqueueAfter(
                new OutboundActivity(delete.id().toString(), author.get().id(), json.writeValueAsString(delete)),
                List.copyOf(inboxes),
                create);
    }

    /**
     * Where to send a status's activities. Its {@code Create} and {@code Delete} have to use the same rule, or a follower
     * could be sent a status and not its deletion. Statuses that anyone can see can be fetched, so a later follower may have
     * a copy to remove.
     *
     * <p>The author of a public or unlisted status's parent on another server gets them as well, since otherwise their server
     * wouldn't know about the reply. A followers-only reply isn't sent to them: whether it addressed them depends on whether
     * its parent could be seen by everyone, which can't be known once it's deleted, and its {@code Delete} has to reach
     * whoever got it.
     *
     * <p>The accounts a status mentions get them whatever its visibility, and they're all a direct status goes to. Mentions
     * are kept, and can't change, so its {@code Delete} reaches the same accounts as its {@code Create}.
     *
     * @param parentAuthorId the parent's author, or null if the status doesn't address them
     * @param mentioned      the accounts the status mentions
     */
    private List<URI> inboxes(
            UUID authorId, Visibility visibility, Instant publishedAt, UUID parentAuthorId, Collection<Actor> mentioned) {
        var inboxes = new LinkedHashSet<URI>();
        recipients.addFollowers(authorId, visibility, publishedAt, inboxes);
        if (parentAuthorId != null && (visibility == Visibility.PUBLIC || visibility == Visibility.UNLISTED)) {
            actors.findById(parentAuthorId).ifPresent(parent -> recipients.add(parent, inboxes));
        }
        mentioned.forEach(actor -> recipients.add(actor, inboxes));
        return List.copyOf(inboxes);
    }
}
