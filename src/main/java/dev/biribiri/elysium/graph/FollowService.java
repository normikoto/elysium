package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.internal.FollowRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who follows whom. A follow can be identified by the activity that created it, which other servers refer to when they undo
 * it.
 *
 * <p>Activities can be processed in any order, and a delivery can be retried, so what counts is when each activity first
 * arrived. An {@code Undo} covers its {@code Follow}, and any that first arrived before it. It's remembered for a while,
 * because a job or a retry of one of those can still come afterward. A {@code Follow} that first arrives later is a new
 * request, so it stands.
 *
 * <p>A local account's follow of a remote account is identified by the {@code Follow} sent for it, which has a new ID for each
 * request. Answers come from the followed actor, and only name that ID or the two actors, so none of the timing above is
 * needed: an answer to a request that's been withdrawn finds nothing to change.
 */
@Service
public class FollowService {

    private static final int REMOVAL_BATCH_SIZE = 1000;

    private final FollowRepository follows;
    private final ActorService actors;
    private final ApplicationEventPublisher events;

    FollowService(FollowRepository follows, ActorService actors, ApplicationEventPublisher events) {
        this.follows = follows;
        this.actors = actors;
        this.events = events;
    }

    /**
     * Makes a local account follow another account. A local account is followed straight away, since nobody can approve
     * followers by hand yet. An account on another server has to answer first, so the follow waits for that, and
     * {@link FollowRequested} is published so that it's asked. Asking again while it's waiting publishes that again, as its
     * server may have lost the request. Following an account already followed does nothing.
     *
     * <p>The caller checks that both accounts can take part, since this has no view of domain blocks.
     *
     * @param follower a local account
     * @param followed any other account
     */
    @Transactional
    public void follow(Actor follower, Actor followed) {
        if (follower.id().equals(followed.id())) {
            throw new IllegalArgumentException("An account can't follow itself");
        }
        follows.lock(pairKey(follower.uri().toString(), followed.id()));
        var existing = follows.findByPair(follower.id(), followed.id());
        if (existing.isPresent()) {
            // Only a follow of a remote account waits for an answer.
            if (!existing.get().accepted()) {
                events.publishEvent(new FollowRequested(
                        follower.id(), followed.id(), existing.get().activityUri(), Instant.now()));
            }
            return;
        }
        var id = UUID.randomUUID();
        if (actors.isLocal(followed)) {
            if (follows.insertOutgoing(id, follower.id(), followed.id(), null)) {
                events.publishEvent(new FollowEstablished(id, follower.id(), followed.id()));
            }
            return;
        }
        var activityUri = followActivityUri(follower, id);
        follows.insertOutgoing(id, follower.id(), followed.id(), activityUri);
        events.publishEvent(new FollowRequested(follower.id(), followed.id(), activityUri, Instant.now()));
    }

    /**
     * The {@code id} of the {@code Follow} sent for a follow. Each follow gets a new one, so that an answer to an earlier
     * request can't be taken for an answer to this one.
     */
    private static String followActivityUri(Actor follower, UUID followId) {
        return follower.uri() + "#follows/" + followId;
    }

    /**
     * Stops a local account following another account, or withdraws its request to. For an account on another server, this
     * publishes {@link FollowWithdrawn}, so that its server is told.
     *
     * @return whether there was a follow or request to remove
     */
    @Transactional
    public boolean unfollow(Actor follower, UUID followedId) {
        follows.lock(pairKey(follower.uri().toString(), followedId));
        var removed = follows.deletePair(follower.id(), followedId);
        // Only follows of remote accounts have an activity behind them, when the follower is local.
        removed.filter(row -> row.activityUri() != null).ifPresent(row ->
                events.publishEvent(new FollowWithdrawn(follower.id(), followedId, row.activityUri())));
        return removed.isPresent();
    }

    /**
     * Whether a follow by a local account still has this activity behind it, so that a {@code Follow} queued for an earlier
     * request isn't sent.
     */
    public boolean isRequestCurrent(UUID followerId, UUID followedId, String activityUri) {
        return follows.existsOutgoing(followerId, followedId, activityUri);
    }

    /**
     * Applies an {@code Accept} from an account on another server, of a {@code Follow} sent from here.
     *
     * @param followedId the remote account that sent the {@code Accept}
     * @param activityUri the {@code Follow} it names
     * @return whether a follow was waiting for this
     */
    @Transactional
    public boolean acceptRequest(UUID followedId, String activityUri) {
        return follows.acceptOutgoing(followedId, activityUri);
    }

    /**
     * Like {@link #acceptRequest(UUID, String)}, for an {@code Accept} that doesn't say which {@code Follow} it's for.
     */
    @Transactional
    public boolean acceptRequestFrom(UUID followerId, UUID followedId) {
        return follows.acceptOutgoing(followerId, followedId);
    }

    /**
     * Applies a {@code Reject} from an account on another server, which removes the follow, whether or not it was accepted.
     *
     * @param followedId  the remote account that sent the {@code Reject}
     * @param activityUri the {@code Follow} it names
     * @return whether a follow was removed
     */
    @Transactional
    public boolean rejectRequest(UUID followedId, String activityUri) {
        return follows.deleteOutgoing(followedId, activityUri);
    }

    /**
     * Like {@link #rejectRequest(UUID, String)}, for a {@code Reject} that doesn't say which {@code Follow} it's for.
     */
    @Transactional
    public boolean rejectRequestFrom(Actor follower, UUID followedId) {
        follows.lock(pairKey(follower.uri().toString(), followedId));
        return follows.deletePair(follower.id(), followedId).isPresent();
    }

    /**
     * Lists a local account's follows of accounts on other servers, and its requests to follow them, so that they can be
     * withdrawn when the account is deleted.
     */
    public List<SentFollow> sentBy(UUID followerId) {
        return follows.findSent(followerId);
    }

    /**
     * @return the accounts this one follows and has been accepted by, including suspended ones
     */
    public List<UUID> followedIds(UUID followerId) {
        return follows.findFollowedIds(followerId);
    }

    /**
     * Whether one account follows another, and has been accepted.
     */
    public boolean isFollowing(UUID followerId, UUID followedId) {
        return follows.isFollowing(followerId, followedId);
    }

    /**
     * Whether one account follows another, and did already at this time. These are the followers that a followers-only
     * status posted then was sent to, through {@link #followerInboxesAsOf}.
     */
    public boolean wasFollowingAsOf(UUID followerId, UUID followedId, Instant time) {
        return follows.wasFollowingAsOf(followerId, followedId, time);
    }

    /**
     * @return how the viewer is related to each of these accounts, in the same order
     */
    public List<Relationship> relationships(UUID viewerId, List<UUID> otherIds) {
        var outgoing = new HashMap<UUID, Boolean>();
        var followedBy = new HashSet<UUID>();
        for (var pair : follows.findPairsWith(viewerId, otherIds)) {
            if (pair.followerId().equals(viewerId)) {
                outgoing.put(pair.followedId(), pair.accepted());
            } else if (pair.accepted()) {
                followedBy.add(pair.followerId());
            }
        }
        return otherIds.stream().map(other -> new Relationship(
                other,
                Boolean.TRUE.equals(outgoing.get(other)),
                Boolean.FALSE.equals(outgoing.get(other)),
                followedBy.contains(other))).toList();
    }

    /**
     * Lists an account's accepted followers, newest first, leaving out suspended and deleted ones.
     */
    public List<FollowEntry> followers(UUID actorId, FollowPage page) {
        return follows.findPage(actorId, true, page);
    }

    /**
     * Lists the accounts that an account follows and has been accepted by, newest first, leaving out suspended and deleted
     * ones.
     */
    public List<FollowEntry> following(UUID actorId, FollowPage page) {
        return follows.findPage(actorId, false, page);
    }

    /**
     * Counts accepted follows to and from each of these accounts, leaving out suspended and deleted actors.
     *
     * @return an entry for each account
     */
    public Map<UUID, FollowCounts> countsFor(Collection<UUID> actorIds) {
        return follows.countFor(actorIds);
    }

    /**
     * Counts an account's accepted followers, leaving out suspended and deleted ones.
     */
    public long countFollowers(UUID actorId) {
        return follows.count(List.of(actorId), true).getOrDefault(actorId, 0L);
    }

    /**
     * Counts the accounts that an account follows and has been accepted by, leaving out suspended and deleted ones.
     */
    public long countFollowing(UUID actorId) {
        return follows.count(List.of(actorId), false).getOrDefault(actorId, 0L);
    }

    /**
     * Stores an accepted follow, unless it's been undone, or the follow already comes from an activity that first arrived
     * later.
     * This takes part in the caller's transaction, so that the caller can save its answer along with it.
     *
     * @return what happened if this activity is now the one behind the follow, or empty if it should get no answer
     * @throws org.springframework.transaction.IllegalTransactionStateException if there's no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Accepted> accept(UUID followerId, URI followerUri, UUID followedId, String activityUri, Arrival arrival) {
        var follower = followerUri.toString();
        // The caller checks wasUndone before fetching anything, but an Undo can run after that, or be running right now.
        // Without the locks, neither transaction would see what the other one is doing. They're always taken in this order.
        follows.lock(pairKey(follower, followedId));
        follows.lock(activityUri);
        if (wasUndone(activityUri, follower, followedId, arrival)) {
            return Optional.empty();
        }
        var existing = follows.findByPair(followerId, followedId);
        if (existing.isPresent() && existing.get().activityAt().isAfter(arrival.first())) {
            return Optional.empty();
        }
        follows.saveAccepted(followerId, followedId, activityUri, arrival.first());
        if (existing.isEmpty() || !existing.get().accepted()) {
            var saved = follows.findByPair(followerId, followedId).orElseThrow();
            events.publishEvent(new FollowEstablished(saved.id(), followerId, followedId));
        }
        return Optional.of(new Accepted(existing
                .map(FollowRepository.Row::activityUri)
                .filter(previous -> !previous.equals(activityUri))));
    }

    /**
     * Whether an activity has been undone. {@link #accept} checks too, so this is only for avoiding unnecessary work.
     */
    public boolean wasUndone(String activityUri, String followerUri, UUID followedId, Arrival arrival) {
        // By first arrival, because a repeat of a Follow that was undone is a retry and not a new request. Servers make a new
        // ID for a new request.
        return follows.isUndone(activityUri, followerUri)
                || follows.isPairUndone(followerUri, followedId, arrival.first());
    }

    /**
     * Applies an {@code Undo} that names the activity behind a follow.
     *
     * @param followedId who the activity was a follow of, if that's known. This removes any follow between the two actors
     *                   that first arrived before the {@code Undo}, if the activity itself isn't known.
     * @param arrival    when the activity arrived, if that's known. It may not have arrived yet.
     * @param undoAt     when the {@code Undo} arrived
     * @return the follow that was removed, if any
     */
    @Transactional
    public Optional<Removed> undo(
            String followerUri, Optional<UUID> followedId, String activityUri, Optional<Arrival> arrival, Instant undoAt) {
        var follower = actors.findByUri(URI.create(followerUri));
        var stored = follower.flatMap(actor -> follows.findByActivity(actor.id(), activityUri));
        var followed = stored.map(FollowRepository.Row::followedId).or(() -> followedId);
        followed.ifPresent(id -> follows.lock(pairKey(followerUri, id)));
        follows.lock(activityUri);

        // Stored activities are eventually cleaned up, long before an account stops following someone.
        var known = arrival.or(() -> stored.map(row -> new Arrival(row.activityAt(), row.activityAt())));
        if (known.isEmpty() && followed.isPresent()) {
            // Either the Follow hasn't arrived, or this isn't the ID that it has here, since a server can make a new one. The
            // actor has still stopped following, and staying a follower by mistake is worse than losing one.
            follows.markUndone(activityUri, followerUri, undoAt);
            return cover(followerUri, followed.get(), undoAt);
        }
        if (known.isEmpty() || known.get().first().isAfter(undoAt)) {
            // The Undo got here first, so nothing else says what it covers. Only this activity can be stopped.
            follows.markUndone(activityUri, followerUri, undoAt);
            return follower.flatMap(actor -> follows.deleteByActivity(actor.id(), activityUri))
                    .map(followedActor -> new Removed(followedActor, activityUri));
        }
        if (followed.isEmpty()) {
            return Optional.empty();
        }
        // A Follow that first arrived after this one is a newer follow, even if this one was repeated after that.
        return cover(followerUri, followed.get(), known.get().first());
    }

    /**
     * Applies an {@code Undo} that doesn't say which activity created a follow, which covers all that first arrived before
     * it. Nothing can tell one that first arrives afterward from a new request to follow.
     *
     * @param undoAt when the {@code Undo} arrived
     * @return the follow that was removed, if any
     */
    @Transactional
    public Optional<Removed> undoPair(String followerUri, UUID followedId, Instant undoAt) {
        follows.lock(pairKey(followerUri, followedId));
        return cover(followerUri, followedId, undoAt);
    }

    /**
     * Where to deliver something to an actor's remote followers, with one inbox for followers that share one.
     */
    public List<URI> followerInboxes(UUID followedId) {
        return follows.findFollowerInboxes(followedId);
    }

    /**
     * @return the local accounts that follow an actor and are neither suspended nor deleted
     */
    public List<UUID> localFollowerIds(URI followedUri) {
        return follows.findLocalFollowerIds(followedUri);
    }

    /**
     * @return whether a local account that's neither suspended nor deleted follows the actor
     */
    public boolean hasLocalFollowers(URI followedUri) {
        return follows.hasLocalFollowers(followedUri);
    }

    /**
     * Like {@link #followerInboxes}, leaving out actors who started following after this time.
     */
    public List<URI> followerInboxesAsOf(UUID followedId, Instant time) {
        return follows.findFollowerInboxesAsOf(followedId, time);
    }

    /**
     * @return the domain of every remote actor, each as {@code actor.domain} has it
     */
    public List<String> remoteDomains() {
        return follows.findRemoteDomains();
    }

    /**
     * Removes all follows to and from a domain's remote actors. Each batch of actors commits by itself, so that removing a
     * lot of them doesn't hold one long transaction.
     *
     * @param domain a domain from {@link #remoteDomains}
     */
    public void removeForDomain(String domain) {
        var after = new UUID(0, 0);
        List<UUID> actorIds;
        do {
            actorIds = follows.findRemoteActorIds(domain, after, REMOVAL_BATCH_SIZE);
            if (!actorIds.isEmpty()) {
                follows.deleteForActors(actorIds);
                after = actorIds.getLast();
            }
        } while (actorIds.size() == REMOVAL_BATCH_SIZE);
    }

    /**
     * Removes all follows to and from an actor.
     */
    public void removeForActor(UUID actorId) {
        follows.deleteForActors(List.of(actorId));
    }

    private Optional<Removed> cover(String followerUri, UUID followedId, Instant arrivedBy) {
        follows.markPairUndone(followerUri, followedId, arrivedBy);
        return actors.findByUri(URI.create(followerUri))
                .flatMap(follower -> follows.deleteByPair(follower.id(), followedId, arrivedBy))
                .map(activityUri -> new Removed(followedId, activityUri));
    }

    private static String pairKey(String followerUri, UUID followedId) {
        return followerUri + "\n" + followedId;
    }

    /**
     * When an activity arrived. These differ if it was repeated.
     */
    public record Arrival(Instant first, Instant last) {
    }

    /**
     * @param replacedActivity the activity that was behind the follow until now, if there was a different one
     */
    public record Accepted(Optional<String> replacedActivity) {
    }

    public record Removed(UUID followedId, String activityUri) {
    }
}
