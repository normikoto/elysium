package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Create;
import dev.biribiri.elysium.activitypub.vocab.Delete;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusDeleted;
import dev.biribiri.elysium.content.StatusEdited;
import dev.biribiri.elysium.content.StatusPublished;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.BlockService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sends new, edited, and deleted statuses to the author's followers and the accounts they mention on other servers. This can
 * run again for the same status, which only sends it again if its jobs have finished, and other servers ignore an
 * {@code id} they've already seen.
 */
@Service
public class StatusDeliveries {

    private final StatusService statuses;
    private final ActorService actors;
    private final Recipients recipients;
    private final Notes notes;
    private final BlockService blocks;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    StatusDeliveries(
            StatusService statuses,
            ActorService actors,
            Recipients recipients,
            Notes notes,
            BlockService blocks,
            ActivityDeliveries deliveries,
            JsonMapper json) {
        this.statuses = statuses;
        this.actors = actors;
        this.recipients = recipients;
        this.notes = notes;
        this.blocks = blocks;
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
                mentioned,
                true);
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
     * accounts that boosted it to remove it, after any {@code Create} or {@code Update} still being sent. A server that gets
     * an {@code Update} of a status it doesn't have can store it, as Mastodon does, so one that arrived after this would
     * bring the status back. Which followers were sent the status isn't kept, so this goes to current followers, as
     * {@link #inboxes} decides. An author who's been suspended since can come back, and this waits for that, so that
     * followers don't keep what was deleted.
     */
    @ApplicationModuleListener
    public void on(StatusDeleted event) {
        var create = Create.idFor(event.statusUri());
        var updates = Update.idPrefixFor(event.statusUri());
        deliveries.cancel(create);
        deliveries.cancelStartingWith(updates);
        var author = actors.findAnyLocalById(event.authorId()).filter(found -> !found.isDeleted());
        if (author.isEmpty()) {
            return;
        }
        var mentioned = actors.findByIds(event.mentioned());
        var inboxes = everyoneSent(
                author.get().id(), event.visibility(), event.publishedAt(), event.inReplyToAccountId(), mentioned,
                event.boosters(), false);
        if (inboxes.isEmpty()) {
            return;
        }
        var delete = Delete.of(
                event.statusUri(), author.get(), event.visibility(), mentioned.stream().map(Actor::uri).toList());
        deliveries.enqueueAfter(
                new OutboundActivity(delete.id().toString(), author.get().id(), json.writeValueAsString(delete)),
                inboxes,
                create,
                updates);
    }

    /**
     * Sends the status as it is now to everyone its {@code Delete} would go to, which includes the accounts an edit has
     * stopped mentioning, since they still have it, and those it's started to. The ID comes from the edit, and the body from
     * what's true when this runs, so a repeat, or a run after a later edit, sends what's current under this edit's ID.
     * Servers that get two keep the one whose {@code updated} is later.
     *
     * <p>A {@code Create} that's still queued is changed to carry the edit too, and this doesn't overtake one being sent,
     * since a server that doesn't have the status yet can ignore this, as Mastodon does. The status can't be edited while
     * this runs, so that this can't write an older version over the one a later edit's listener wrote.
     *
     * <p>Like the {@code Create}, this is dropped if the author is suspended before it's sent, so that it doesn't tell
     * servers that never got the {@code Create} about the status once the suspension ends.
     */
    @ApplicationModuleListener
    public void on(StatusEdited event) {
        var status = statuses.findAndHoldAgainstEdits(event.statusId(), event.authorId());
        var author = actors.findLocalById(event.authorId());
        if (status.isEmpty() || author.isEmpty()) {
            return;
        }
        var note = notes.of(status.get(), author.get());
        var create = Create.of(note);
        deliveries.replaceBody(create.id().toString(), json.writeValueAsString(create));
        var inboxes = everyoneSent(
                author.get().id(),
                status.get().visibility(),
                status.get().publishedAt(),
                note.inReplyTo() == null ? null : status.get().inReplyToAccountId(),
                actors.findByIds(statuses.findAllMentioned(status.get().id())),
                statuses.findLocalBoosters(status.get().id()),
                true);
        if (inboxes.isEmpty()) {
            return;
        }
        var update = Update.of(note, event.editedAt());
        deliveries.enqueueAfter(
                new OutboundActivity(
                        update.id().toString(), author.get().id(), json.writeValueAsString(update), WhileSuspended.DROP),
                inboxes,
                create.id().toString());
    }

    /**
     * Who can have a status: who it was sent to, as {@link #inboxes} decides, and the followers of local accounts that
     * boosted it, who can have it from their boosts. Its {@code Update}s and {@code Delete} have to use the same rule.
     *
     * @param mentioned every account it mentions, silently or not
     */
    private List<URI> everyoneSent(
            UUID authorId, Visibility visibility, Instant publishedAt, UUID parentAuthorId, Collection<Actor> mentioned,
            List<UUID> boosters, boolean withoutBlocked) {
        var inboxes = new LinkedHashSet<>(
                inboxes(authorId, visibility, publishedAt, parentAuthorId, mentioned, withoutBlocked));
        boosters.forEach(booster -> recipients.addFollowers(booster, visibility, publishedAt, inboxes));
        return List.copyOf(inboxes);
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
     * <p>The accounts a status mentions get them whatever its visibility, and they're all a direct status goes to. An edit
     * can add mentions, but those it removes are kept as silent ones, so its {@code Delete} reaches every account that was
     * sent its {@code Create} or an {@code Update}.
     *
     * <p>A {@code Create} or {@code Update} isn't sent to a parent's author or a mentioned account when either blocks the
     * other, since a server that gets an {@code Update} can store the status. A {@code Delete} still goes to them, because
     * they can have had the status from before the block.
     *
     * @param parentAuthorId the parent's author, or null if the status doesn't address them
     * @param mentioned      the accounts the status mentions
     * @param withoutBlocked whether to leave out the parent's author and mentioned accounts in a block with the author
     */
    private List<URI> inboxes(
            UUID authorId, Visibility visibility, Instant publishedAt, UUID parentAuthorId, Collection<Actor> mentioned,
            boolean withoutBlocked) {
        var inboxes = new LinkedHashSet<URI>();
        recipients.addFollowers(authorId, visibility, publishedAt, inboxes);
        var addressed = new ArrayList<Actor>();
        if (parentAuthorId != null && (visibility == Visibility.PUBLIC || visibility == Visibility.UNLISTED)) {
            actors.findById(parentAuthorId).ifPresent(addressed::add);
        }
        addressed.addAll(mentioned);
        Set<UUID> blocked = withoutBlocked
                ? blocks.inBlockWith(authorId, addressed.stream().map(Actor::id).toList())
                : Set.of();
        for (var actor : addressed) {
            if (!blocked.contains(actor.id())) {
                recipients.add(actor, inboxes);
            }
        }
        return List.copyOf(inboxes);
    }
}
