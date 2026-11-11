package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Create;
import dev.biribiri.elysium.activitypub.vocab.Delete;
import dev.biribiri.elysium.activitypub.vocab.Note;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusDeleted;
import dev.biribiri.elysium.content.StatusEdited;
import dev.biribiri.elysium.content.StatusPublished;
import dev.biribiri.elysium.content.StatusQuote;
import dev.biribiri.elysium.content.FollowersOnlyRecipients;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
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
    private final FollowService follows;
    private final FollowersOnlyRecipients followersOnly;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    StatusDeliveries(
            StatusService statuses,
            ActorService actors,
            Recipients recipients,
            Notes notes,
            BlockService blocks,
            FollowService follows,
            FollowersOnlyRecipients followersOnly,
            ActivityDeliveries deliveries,
            JsonMapper json) {
        this.statuses = statuses;
        this.actors = actors;
        this.recipients = recipients;
        this.notes = notes;
        this.blocks = blocks;
        this.follows = follows;
        this.followersOnly = followersOnly;
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
        if (status.get().visibility() == Visibility.PRIVATE) {
            followersOnly.record(
                    status.get().id(),
                    author.get().id(),
                    follows.remoteFollowerIdsAsOf(author.get().id(), status.get().publishedAt()));
        }
        var inboxes = inboxes(
                status.get().id(),
                author.get().id(),
                status.get().visibility(),
                status.get().publishedAt(),
                note.inReplyTo() == null ? null : status.get().inReplyToAccountId(),
                // Silent mentions too, which a quote gives the quoted author.
                actors.findByIds(statuses.findAllMentioned(status.get().id())),
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
     * bring the status back. An author who's been suspended since can come back, and this waits for that, so that
     * followers don't keep what was deleted.
     */
    @ApplicationModuleListener
    public void on(StatusDeleted event) {
        var create = Create.idFor(event.statusUri());
        var updates = Update.idPrefixFor(event.statusUri());
        deliveries.cancel(create);
        deliveries.cancelStartingWith(updates);
        deliveries.cancel(StatusQuote.requestUriFor(event.statusUri()).toString());
        var author = actors.findAnyLocalById(event.authorId()).filter(found -> !found.isDeleted());
        if (author.isEmpty()) {
            followersOnly.forget(event.statusId());
            return;
        }
        var mentioned = actors.findByIds(event.mentioned());
        var inboxes = everyoneSent(
                event.statusId(), author.get().id(), event.visibility(), event.publishedAt(), event.inReplyToAccountId(),
                mentioned, event.boosters(), false);
        followersOnly.forget(event.statusId());
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
        sendUpdate(status.get(), author.get(), note, Update.of(note, event.editedAt()));
    }

    /**
     * @param note   the status as it is now, for a {@code Create} that's still queued
     * @param update what to send. The caller builds it because its {@code Note} can say it changed later than {@code note}
     *               does, and the {@code Create} shouldn't say that of a status that hasn't been edited.
     */
    void sendUpdate(Status status, Actor author, Note note, Update update) {
        var create = Create.of(note);
        deliveries.replaceBody(create.id().toString(), json.writeValueAsString(create));
        var inboxes = everyoneSent(status, true);
        if (inboxes.isEmpty()) {
            return;
        }
        deliveries.enqueueAfter(
                new OutboundActivity(
                        update.id().toString(), author.id(), json.writeValueAsString(update), WhileSuspended.DROP),
                inboxes,
                create.id().toString());
    }

    /**
     * Everyone that a status's {@code Update}s go to, as for an edit.
     */
    List<URI> everyoneSent(Status status, boolean withoutBlocked) {
        return everyoneSent(
                status.id(),
                status.actorId(),
                status.visibility(),
                status.publishedAt(),
                notes.shownParentAuthor(status),
                actors.findByIds(statuses.findAllMentioned(status.id())),
                statuses.findLocalBoosters(status.id()),
                withoutBlocked);
    }

    /**
     * Who can have a status: who it was sent to, as {@link #inboxes} decides, and the followers of local accounts that
     * boosted it, who can have it from their boosts. Its {@code Update}s and {@code Delete} have to use the same rule.
     *
     * @param mentioned every account it mentions, silently or not
     */
    private List<URI> everyoneSent(
            UUID statusId, UUID authorId, Visibility visibility, Instant publishedAt, UUID parentAuthorId,
            Collection<Actor> mentioned, List<UUID> boosters, boolean withoutBlocked) {
        var inboxes = new LinkedHashSet<>(
                inboxes(statusId, authorId, visibility, publishedAt, parentAuthorId, mentioned, withoutBlocked));
        boosters.forEach(booster -> recipients.addFollowers(booster, visibility, publishedAt, inboxes));
        return List.copyOf(inboxes);
    }

    /**
     * Where to send a status's activities. Its {@code Create} and {@code Delete} have to use the same rule, or a follower
     * could be sent a status and not its deletion. Statuses that anyone can see can be fetched, so a later follower may have
     * a copy to remove.
     *
     * <p>A followers-only status goes to those already following when it was posted, so that its activities can't tell
     * anyone else that it existed. Follows can't say who that was once someone has unfollowed, or followed again, so its
     * {@code Create} records them. Its {@code Delete} goes to everyone recorded, and its {@code Update}s only to those
     * who still follow, since the others shouldn't see an edit. Those already following when it was posted are included
     * either way, which covers statuses from before anything was recorded.
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
     * @param withoutBlocked whether this is for a {@code Create} or {@code Update}, which leaves out the parent's author and
     *                       mentioned accounts in a block with the author, and former followers
     */
    private List<URI> inboxes(
            UUID statusId, UUID authorId, Visibility visibility, Instant publishedAt, UUID parentAuthorId,
            Collection<Actor> mentioned, boolean withoutBlocked) {
        var inboxes = new LinkedHashSet<URI>();
        if (visibility == Visibility.PRIVATE) {
            var followedThen = follows.remoteFollowerIdsAsOf(authorId, publishedAt);
            inboxes.addAll(followersOnly.inboxes(
                    statusId, followedThen, withoutBlocked ? follows.remoteFollowerIds(authorId) : null));
        } else {
            recipients.addFollowers(authorId, visibility, publishedAt, inboxes);
        }
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
