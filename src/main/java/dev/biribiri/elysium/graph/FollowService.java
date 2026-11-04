package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.internal.FollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
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
 */
@Service
public class FollowService {

    private final FollowRepository follows;
    private final ActorService actors;

    FollowService(FollowRepository follows, ActorService actors) {
        this.follows = follows;
        this.actors = actors;
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
