package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.BoostRepository;
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

/**
 * Boosts of stored posts. A boost is a status of its own, with no text, whose ID places it in timelines.
 *
 * <p>As on Mastodon, anyone who can see a public or unlisted status can boost it, and an author can boost their own
 * followers-only status, which only reaches their followers. Nobody else can boost a followers-only status, because that
 * would show it to people its author didn't send it to.
 */
@Service
public class BoostService {

    private final BoostRepository boosts;
    private final LikeRepository likes;
    private final StatusRepository repository;
    private final StatusService statuses;
    private final ActorService actors;
    private final ApplicationEventPublisher events;

    BoostService(BoostRepository boosts, LikeRepository likes, StatusRepository repository, StatusService statuses,
                 ActorService actors, ApplicationEventPublisher events) {
        this.boosts = boosts;
        this.likes = likes;
        this.repository = repository;
        this.statuses = statuses;
        this.actors = actors;
        this.events = events;
    }

    /**
     * Boosts a status for a local account. Boosting a boost boosts the status it boosts, as on Mastodon, and boosting a
     * status again returns the boost that's there.
     *
     * @param visibility who can see the boost, which is followers-only for a followers-only status whatever this says.
     *                   Never direct
     * @return the boost, or empty if the account can't see or can't boost the status
     */
    @Transactional
    public Optional<Status> reblog(UUID statusId, UUID actorId, Visibility visibility) {
        if (visibility == Visibility.DIRECT) {
            throw new IllegalArgumentException("A boost can't be direct");
        }
        return original(statusId).flatMap(id -> holdBoostable(id, actorId)).flatMap(status ->
                actors.findLocalById(actorId).flatMap(actor -> {
                    boosts.lockPair(actor.uri(), status.id());
                    var existing = boosts.findPair(actorId, status.id());
                    if (existing.isPresent()) {
                        return repository.findById(existing.get().id());
                    }
                    var publishedAt = now();
                    var id = statuses.nextId(publishedAt);
                    var boost = boost(id, URI.create(actor.uri() + "/statuses/" + id + "/activity"), actorId, status,
                            status.visibility() == Visibility.PRIVATE ? Visibility.PRIVATE : visibility, publishedAt);
                    repository.insert(boost);
                    events.publishEvent(new StatusReblogged(id, actorId, status.id(), status.actorId()));
                    return Optional.of(boost);
                }));
    }

    /**
     * Removes a local account's boost of a status, if it has one. A boost's ID can stand in for the status it boosts.
     *
     * @return the status that was boosted, or empty if the account can't see it
     */
    @Transactional
    public Optional<Status> unreblog(UUID statusId, UUID actorId) {
        return original(statusId).flatMap(id -> holdVisible(id, actorId)).flatMap(status ->
                actors.findLocalById(actorId).map(actor -> {
                    boosts.lockPair(actor.uri(), status.id());
                    boosts.findPair(actorId, status.id()).ifPresent(boost -> {
                        boosts.delete(boost.id());
                        events.publishEvent(StatusUnreblogged.of(boost, status.uri(), status.actorId()));
                    });
                    return status;
                }));
    }

    /**
     * @return the status a boost boosts, or the ID itself if it isn't a boost's
     */
    public Optional<UUID> original(UUID statusId) {
        return repository.findById(statusId).map(status -> status.isReblog() ? status.reblogOfId() : status.id());
    }

    /**
     * Finds a local status that a boost from another server can name, which isn't itself a boost.
     */
    public Optional<Status> localTarget(URI uri) {
        return likes.localStatusId(uri).flatMap(repository::findById);
    }

    /**
     * Finds a stored status that a boost from another server can name, from this server or another, which isn't itself a
     * boost.
     */
    public Optional<Status> storedTarget(URI uri) {
        return boosts.findStatusIdByUri(uri).flatMap(repository::findById);
    }

    /**
     * Whether an {@code Announce} has been undone or deleted, so that it isn't worth fetching what it boosts.
     */
    public boolean wasWithdrawn(URI announceUri, URI actorUri) {
        return repository.isRemoteDeleted(announceUri, actorUri);
    }

    /**
     * Whether this is one of the account's own boosts.
     */
    public boolean isOwnBoost(UUID statusId, UUID actorId) {
        return boosts.find(statusId).filter(boost -> boost.actorId().equals(actorId)).isPresent();
    }

    /**
     * Stores a boost from another server. Only the first {@code Announce} of a status to arrive from an actor makes a
     * boost, which a later one replaces the ID of, so that either one's {@code Undo} still finds it. An {@code Announce}
     * that's been undone already, or deleted, is ignored, since jobs have no order.
     *
     * @param statusId     the stored status it boosts
     * @param announceUri  the {@code Announce}'s ID
     * @param firstArrival when the {@code Announce} first arrived, which retries don't change
     */
    @Transactional
    public void receive(UUID boosterId, UUID statusId, URI announceUri, Visibility visibility, Instant publishedAt,
                        Instant firstArrival) {
        if (visibility == Visibility.DIRECT) {
            return;
        }
        // As PostgreSQL stores it, so that comparing with a stored arrival gives the same answer every time.
        var arrival = firstArrival.truncatedTo(ChronoUnit.MICROS);
        holdBoostable(statusId, boosterId).ifPresent(status -> {
            var booster = actors.findById(boosterId).orElseThrow();
            if (actors.isLocal(booster)) return;
            repository.lockUri(announceUri);
            if (repository.isRemoteDeleted(announceUri, booster.uri())) return;
            boosts.lockPair(booster.uri(), statusId);
            var existing = boosts.findPair(boosterId, statusId);
            if (existing.isPresent()) {
                var current = existing.get();
                if (!current.uri().equals(announceUri) && !repository.existsByUri(announceUri)
                        && !current.activityAt().isAfter(arrival)) {
                    boosts.replaceActivity(current.id(), announceUri, arrival);
                }
                return;
            }
            var id = statuses.nextId(Instant.now());
            var boost = boost(id, announceUri, boosterId, status,
                    status.visibility() == Visibility.PRIVATE ? Visibility.PRIVATE : visibility,
                    publishedAt.truncatedTo(ChronoUnit.MICROS));
            if (repository.insert(boost, arrival)) {
                events.publishEvent(new StatusReblogged(id, boosterId, statusId, status.actorId()));
            }
        });
    }

    /**
     * Removes a remote boost because its actor undid its {@code Announce}, and remembers that it did, since jobs have no
     * order and the {@code Announce} can still be waiting to run. An {@code Undo} of an {@code Announce} that a newer one
     * replaced leaves the boost alone.
     *
     * @param actorUri the actor that sent the {@code Undo}, which can't remove anyone else's boost
     */
    @Transactional
    public void undo(URI actorUri, URI announceUri) {
        repository.lockUri(announceUri);
        repository.markRemoteDeleted(announceUri, actorUri);
        // The pair's lock, so that this can't miss a newer Announce that's replacing this one's ID.
        boosts.findByUri(actorUri, announceUri).ifPresent(boost -> {
            boosts.lockPair(actorUri, boost.statusId());
            boosts.deleteRemote(actorUri, announceUri);
        });
    }

    /**
     * Finds a local account's boost, and holds it and the status it boosts until the transaction ends, so that an outbound
     * listener can queue its {@code Announce} without an unboost being sent before it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StatusBoost> findAndHold(UUID boostId) {
        var preview = boosts.find(boostId);
        if (preview.isEmpty()) return Optional.empty();
        var boost = preview.get();
        // A suspended local account keeps its boosts, as it keeps its statuses.
        var actor = actors.findAnyLocalById(boost.actorId()).filter(found -> !found.isDeleted());
        var status = repository.findById(boost.statusId());
        if (actor.isEmpty() || status.isEmpty()) return Optional.empty();
        likes.holdActors(List.of(boost.actorId(), status.get().actorId()));
        if (!likes.eligibleActor(status.get().actorId()) || !likes.holdStatus(boost.statusId())) return Optional.empty();
        boosts.lockPair(actor.get().uri(), boost.statusId());
        return boosts.find(boostId);
    }

    public Map<UUID, BoostStats> stats(Collection<UUID> ids, Optional<UUID> viewer) {
        return boosts.stats(ids, viewer);
    }

    /**
     * Counts the boosts of a status that anyone can see.
     */
    public long countPublic(UUID statusId) {
        return boosts.countPublic(statusId);
    }

    /**
     * Lists the boosts of a status that anyone can see, newest first.
     */
    public List<StatusBoost> pagePublic(UUID statusId, UUID maxId, int limit) {
        return boosts.pagePublic(statusId, maxId, limit);
    }

    private static Status boost(UUID id, URI uri, UUID actorId, Status status, Visibility visibility, Instant publishedAt) {
        return new Status(id, uri, actorId, "", "", "", null, visibility, false, false, null, null, null, null,
                publishedAt, null, status.id(), 0, null, null);
    }

    /**
     * Finds a status this actor can boost, after holding both accounts and the status, in the order that cascading
     * deletion takes them.
     */
    private Optional<Status> holdBoostable(UUID statusId, UUID actorId) {
        return holdVisible(statusId, actorId).filter(status -> switch (status.visibility()) {
            case PUBLIC, UNLISTED -> true;
            case PRIVATE -> status.actorId().equals(actorId);
            case DIRECT -> false;
        });
    }

    private Optional<Status> holdVisible(UUID statusId, UUID actorId) {
        var preview = repository.findById(statusId).filter(status -> !status.isReblog());
        if (preview.isEmpty()) return Optional.empty();
        likes.holdActors(List.of(actorId, preview.get().actorId()));
        if (!likes.eligibleActor(actorId) || !likes.eligibleActor(preview.get().actorId())) return Optional.empty();
        return repository.findByIdAndLock(statusId).filter(status -> statuses.isVisible(status, Optional.of(actorId)));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
