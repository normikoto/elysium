package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Announce;
import dev.biribiri.elysium.activitypub.vocab.Undo;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.StatusReblogged;
import dev.biribiri.elysium.content.FollowersOnlyRecipients;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusUnreblogged;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
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
 * Sends local boosts to the booster's followers and the boosted status's author, and withdraws them from the same servers.
 * Either can run again, and other servers ignore an {@code id} they've already seen.
 */
@Service
public class BoostDeliveries {

    private final BoostService boosts;
    private final StatusService statuses;
    private final ActorService actors;
    private final Recipients recipients;
    private final FollowService follows;
    private final FollowersOnlyRecipients followersOnly;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;

    BoostDeliveries(BoostService boosts, StatusService statuses, ActorService actors, Recipients recipients,
                    FollowService follows, FollowersOnlyRecipients followersOnly, ActivityDeliveries deliveries,
                    JsonMapper json) {
        this.follows = follows;
        this.followersOnly = followersOnly;
        this.boosts = boosts;
        this.statuses = statuses;
        this.actors = actors;
        this.recipients = recipients;
        this.deliveries = deliveries;
        this.json = json;
    }

    /**
     * What's gone by now has nothing left to send. Holding the boost until this commits means an unboost either finds the
     * {@code Announce} to cancel, or has already removed the boost, so that its {@code Undo} can't be sent first.
     */
    @ApplicationModuleListener
    public void on(StatusReblogged event) {
        var booster = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        if (booster.isEmpty()) return;
        boosts.findAndHold(event.boostId()).ifPresent(boost ->
                statuses.findVisible(boost.statusId(), Optional.of(boost.actorId())).ifPresent(status -> {
                    var author = actors.findById(status.actorId()).orElse(null);
                    var announce = Announce.fromHere(boost.uri(), booster.get(), status.uri(), author, boost.visibility(),
                            boost.publishedAt());
                    if (boost.visibility() == Visibility.PRIVATE) {
                        followersOnly.record(boost.id(), booster.get().id(),
                                follows.remoteFollowerIdsAsOf(booster.get().id(), boost.publishedAt()));
                    }
                    var inboxes = inboxes(boost.id(), booster.get().id(), author, boost.visibility(), boost.publishedAt(),
                            true);
                    if (inboxes.isEmpty()) return;
                    deliveries.enqueue(new OutboundActivity(announce.id(), booster.get().id(),
                            json.writeValueAsString(announce), WhileSuspended.DROP), inboxes);
                }));
    }

    /**
     * Withdraws the boost after any {@code Announce} still being sent. A booster who's been suspended since can come back,
     * and this waits for that, as a status's {@code Delete} does.
     */
    @ApplicationModuleListener
    public void on(StatusUnreblogged event) {
        deliveries.cancel(event.boostUri().toString());
        var booster = actors.findAnyLocalById(event.actorId()).filter(found -> !found.isDeleted());
        if (booster.isEmpty()) {
            followersOnly.forget(event.boostId());
            return;
        }
        var author = actors.findById(event.authorId()).orElse(null);
        var inboxes = inboxes(event.boostId(), booster.get().id(), author, event.visibility(), event.publishedAt(), false);
        followersOnly.forget(event.boostId());
        if (inboxes.isEmpty()) return;
        var undo = Undo.of(Announce.fromHere(event.boostUri(), booster.get(), event.statusUri(), author, event.visibility(),
                event.publishedAt()));
        deliveries.enqueueAfter(new OutboundActivity(undo.id(), booster.get().id(), json.writeValueAsString(undo),
                WhileSuspended.WAIT), inboxes, event.boostUri().toString());
    }

    /**
     * The booster's followers, and the boosted status's author, so that their server can count the boost.
     *
     * <p>A followers-only boost goes to those already following when it was made, so that its {@code Undo} can't tell anyone
     * else about it. Its {@code Announce} records who they were, since follows can't say once someone has unfollowed or
     * followed again, and its {@code Undo} goes to all of them. Those already following when it was made are included
     * either way, which covers boosts from before anything was recorded.
     *
     * @param sending whether this is for the {@code Announce}, which only goes to those who still follow
     */
    private List<URI> inboxes(
            UUID boostId, UUID boosterId, Actor author, Visibility visibility, Instant publishedAt, boolean sending) {
        var inboxes = new LinkedHashSet<URI>();
        if (visibility == Visibility.PRIVATE) {
            inboxes.addAll(followersOnly.inboxes(boostId, follows.remoteFollowerIdsAsOf(boosterId, publishedAt),
                    sending ? follows.remoteFollowerIds(boosterId) : null));
        } else {
            recipients.addFollowers(boosterId, visibility, publishedAt, inboxes);
        }
        if (author != null) recipients.add(author, inboxes);
        return List.copyOf(inboxes);
    }
}
