package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.LikeRepository;
import dev.biribiri.elysium.content.internal.StatusRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Likes of stored posts, with actor-scoped Undo receipts for unordered federation jobs. */
@Service
public class LikeService {
    private final LikeRepository likes;
    private final StatusRepository repository;
    private final StatusService statuses;
    private final ActorService actors;
    private final ApplicationEventPublisher events;

    LikeService(LikeRepository likes, StatusRepository repository, StatusService statuses,
                ActorService actors, ApplicationEventPublisher events) {
        this.likes = likes;
        this.repository = repository;
        this.statuses = statuses;
        this.actors = actors;
        this.events = events;
    }

    @Transactional
    public Optional<Status> favourite(UUID statusId, UUID actorId) {
        return holdVisible(statusId, actorId).flatMap(status -> actors.findLocalById(actorId).map(actor -> {
            likes.lockPair(actor.uri().toString(), statusId);
            if (likes.findPair(actorId, statusId).isEmpty()) {
                var activity = actor.uri() + "#likes/" + UUID.randomUUID();
                var like = likes.insert(actorId, statusId, activity, now());
                events.publishEvent(new StatusLiked(like.id(), actorId, statusId, status.actorId()));
            }
            return status;
        }));
    }

    @Transactional
    public Optional<Status> unfavourite(UUID statusId, UUID actorId) {
        return holdVisible(statusId, actorId).flatMap(status -> actors.findLocalById(actorId).map(actor -> {
            likes.lockPair(actor.uri().toString(), statusId);
            likes.findPair(actorId, statusId).ifPresent(like -> {
                likes.delete(like.id());
                events.publishEvent(new StatusUnliked(actorId, statusId, status.actorId(), status.uri(), like.activityUri()));
            });
            return status;
        }));
    }

    private Optional<Status> holdVisible(UUID statusId, UUID actorId) {
        var preview = repository.findById(statusId);
        if (preview.isEmpty()) return Optional.empty();
        likes.holdActors(List.of(actorId, preview.get().actorId()));
        if (!likes.eligibleActor(actorId) || !likes.eligibleActor(preview.get().actorId())) return Optional.empty();
        return repository.findByIdAndLock(statusId).filter(status -> statuses.isVisible(status, Optional.of(actorId)));
    }

    /** Look up only local posts; an inbound Like must never fetch its target. */
    public Optional<Status> localTarget(URI uri) {
        return likes.localStatusId(uri).flatMap(repository::findById);
    }

    /** Includes posts of inactive authors, since a remote actor may still withdraw a like of one. */
    public Optional<UUID> localStatusId(URI uri) {
        return likes.localStatusId(uri);
    }

    public boolean wasUndone(String activity, String actor, UUID status, Instant firstArrival) {
        return likes.wasUndone(activity, actor, status, firstArrival);
    }

    @Transactional
    public void receive(UUID actorId, UUID statusId, String activity, Instant firstArrival) {
        holdVisible(statusId, actorId).ifPresent(status -> {
            var actor = actors.findById(actorId).orElseThrow();
            if (actors.isLocal(actor) || actors.findLocalById(status.actorId()).isEmpty()) return;
            likes.lockPair(actor.uri().toString(), statusId);
            likes.lockActivity(activity);
            if (wasUndone(activity, actor.uri().toString(), statusId, firstArrival)) return;
            var existing = likes.findPair(actorId, statusId);
            if (existing.isPresent()) {
                if (!existing.get().activityAt().isAfter(firstArrival)) {
                    likes.replaceActivity(existing.get().id(), activity, firstArrival);
                }
                return;
            }
            var like = likes.insert(actorId, statusId, activity, firstArrival);
            events.publishEvent(new StatusLiked(like.id(), actorId, statusId, status.actorId()));
        });
    }

    public Optional<StatusLike> findActivity(String actorUri, String activityUri) {
        return likes.findActivity(actorUri, activityUri);
    }

    /**
     * A known activity covers older likes of the same post, as follows do. An unknown activity with a target covers
     * arrivals up to the Undo; an ID alone only covers that ID. All receipts are scoped to the verified actor.
     */
    @Transactional
    public void undo(String actorUri, String activityUri, Optional<UUID> target,
                     Optional<Instant> firstArrival, Instant undoAt) {
        var existing = activityUri == null ? Optional.<StatusLike>empty() : likes.findActivity(actorUri, activityUri);
        var statusId = existing.map(StatusLike::statusId).or(() -> target);
        // Actors before their children, as in receive and notification projection.
        existing.ifPresent(like -> likes.holdActors(List.of(like.actorId())));
        var held = statusId.filter(likes::holdStatus);
        held.ifPresent(id -> likes.lockPair(actorUri, id));
        if (activityUri != null) {
            likes.lockActivity(activityUri);
            likes.markUndone(activityUri, actorUri, undoAt);
        }
        // Read again under the locks: the Like may have committed while the Undo waited.
        var current = activityUri == null ? Optional.<StatusLike>empty() : likes.findActivity(actorUri, activityUri);
        var arrival = firstArrival.or(() -> existing.map(StatusLike::activityAt)).or(() -> current.map(StatusLike::activityAt));
        if (held.isPresent() && (arrival.isEmpty() || !arrival.get().isAfter(undoAt))) {
            likes.cover(actorUri, held.get(), arrival.orElse(undoAt));
        } else {
            // No pair was known before the activity lock. Another Like may replace this row concurrently,
            // so delete by its activity identity, never just its row ID.
            if (activityUri != null) likes.deleteActivity(actorUri, activityUri);
        }
    }

    /** Hold the pair until an outbound listener has queued its delivery, preventing a late Like after its Undo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StatusLike> findAndHold(UUID likeId) {
        var preview = likes.find(likeId);
        if (preview.isEmpty()) return Optional.empty();
        var like = preview.get();
        var actor = actors.findAnyLocalById(like.actorId()).filter(found -> !found.isDeleted());
        var status = repository.findById(like.statusId());
        if (actor.isEmpty() || status.isEmpty()) return Optional.empty();
        likes.holdActors(List.of(like.actorId(), status.get().actorId()));
        if (!likes.eligibleActor(status.get().actorId()) || !likes.holdStatus(like.statusId())) return Optional.empty();
        // A suspended local sender keeps the like; delivery waits for unsuspension, as it does for follows.
        if (actors.findAnyLocalById(like.actorId()).filter(found -> !found.isDeleted()).isEmpty()) return Optional.empty();
        likes.lockPair(actor.get().uri().toString(), like.statusId());
        return likes.find(likeId);
    }

    public Map<UUID, LikeStats> stats(Collection<UUID> ids, Optional<UUID> viewer) {
        return likes.stats(ids, viewer);
    }

    public long count(UUID statusId) {
        return stats(List.of(statusId), Optional.empty()).getOrDefault(statusId, LikeStats.EMPTY).count();
    }

    public List<StatusLike> page(UUID statusId, UUID maxId, int limit) {
        return likes.page(statusId, maxId, limit);
    }

    public void removeForActor(UUID id) {
        likes.removeForActor(id);
    }

    public void removeForDomain(String domain) {
        var after = new UUID(0, 0);
        List<UUID> batch;
        do {
            batch = likes.actorsOn(domain, after, 1000);
            batch.forEach(likes::removeForActor);
            if (!batch.isEmpty()) after = batch.getLast();
        } while (batch.size() == 1000);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
