package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.StatusPinRepository;
import dev.biribiri.elysium.content.internal.StatusRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Statuses pinned to their authors' profiles, which other servers know as the actor's {@code featured} collection.
 */
@Service
public class StatusPinService {
    /** As on Mastodon, whose clients assume it. */
    public static final int MAX_PER_ACCOUNT = 5;

    /**
     * Remote servers can set their own limits, but what's kept for an actor has to stop somewhere. It's what one fetch of a
     * {@code featured} collection reads.
     */
    public static final int MAX_REMOTE = 40;

    private final StatusPinRepository pins;
    private final StatusRepository repository;
    private final StatusService statuses;
    private final ActorService actors;
    private final ApplicationEventPublisher events;

    StatusPinService(StatusPinRepository pins, StatusRepository repository, StatusService statuses, ActorService actors,
                     ApplicationEventPublisher events) {
        this.pins = pins;
        this.repository = repository;
        this.statuses = statuses;
        this.actors = actors;
        this.events = events;
    }

    /**
     * Pins one of the account's own statuses. The messages of what's refused are Mastodon's.
     *
     * @return the status, or empty if the account can't see it
     * @throws InvalidStatusException if it can't be pinned
     */
    @Transactional
    public Optional<Status> pin(UUID statusId, UUID actorId) {
        var actor = actors.findLocalById(actorId);
        if (actor.isEmpty()) return Optional.empty();
        pins.lockActor(actorId);
        var found = repository.findByIdAndLock(statusId).filter(status -> statuses.isVisible(status, Optional.of(actorId)));
        found.ifPresent(status -> {
            if (status.isReblog()) throw new InvalidStatusException("A boost cannot be pinned");
            if (!status.actorId().equals(actorId)) throw new InvalidStatusException("Someone else's post cannot be pinned");
            if (status.visibility() == Visibility.DIRECT) {
                throw new InvalidStatusException("Posts that are only visible to mentioned users cannot be pinned");
            }
            if (pins.findPair(actorId, statusId).isPresent()) return;
            if (pins.count(actorId) >= MAX_PER_ACCOUNT) {
                throw new InvalidStatusException("You have already pinned the maximum number of posts");
            }
            var pin = pins.insert(actorId, statusId, now(), actor.get().uri() + "#pins/" + UUID.randomUUID());
            events.publishEvent(new StatusPinned(pin.id(), actorId));
        });
        return found;
    }

    /**
     * @return the status, or empty if the account can't see it
     */
    @Transactional
    public Optional<Status> unpin(UUID statusId, UUID actorId) {
        if (actors.findLocalById(actorId).isEmpty()) return Optional.empty();
        pins.lockActor(actorId);
        var found = repository.findById(statusId).filter(status -> statuses.isVisible(status, Optional.of(actorId)));
        found.ifPresent(status -> pins.findPair(actorId, statusId).ifPresent(pin -> {
            pins.delete(pin.id());
            events.publishEvent(new StatusUnpinned(actorId, status.uri(), pin.activityUri(), status.visibility(),
                    status.publishedAt()));
        }));
        return found;
    }

    /**
     * Holds the actor's pins until an outbound listener has queued its {@code Add}, so that a {@code Remove} can't be queued
     * before it.
     *
     * @return the pin, if it's still current
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StatusPin> findAndHold(UUID pinId) {
        var preview = pins.find(pinId);
        if (preview.isEmpty()) return Optional.empty();
        pins.lockActor(preview.get().actorId());
        return pins.find(pinId);
    }

    /**
     * Pins a remote actor's status, unless a {@code Remove} of it arrived after this did.
     *
     * @param actorId a remote actor
     * @param at      when the {@code Add} arrived
     */
    @Transactional
    public void receivePin(UUID actorId, Status status, Instant at) {
        if (!status.actorId().equals(actorId) || status.isReblog()) return;
        pins.lockActor(actorId);
        if (pins.wasRemoved(actorId, status.uri(), at) || pins.findPair(actorId, status.id()).isPresent()
                || pins.count(actorId) >= MAX_REMOTE) {
            return;
        }
        pins.insert(actorId, status.id(), at, null);
    }

    /**
     * Unpins a remote actor's status, which doesn't have to be stored, and keeps an {@code Add} that arrived before this from
     * pinning it later.
     *
     * @param actorId a remote actor
     * @param at      when the {@code Remove} arrived
     */
    @Transactional
    public void receiveUnpin(UUID actorId, URI statusUri, Instant at) {
        pins.lockActor(actorId);
        pins.remove(actorId, statusUri, at);
    }

    /**
     * Replaces a remote actor's pins with what its {@code featured} collection lists. What an {@code Add} or a
     * {@code Remove} that arrived while the collection was being fetched did is kept.
     *
     * @param actorId   a remote actor
     * @param pinnedAt  the statuses that the collection lists, with when to say each was pinned. Collections don't say, so
     *                  the caller makes times that keep their order. Any that aren't the actor's own are left out.
     * @param fetchedAt when the collection was fetched
     */
    @Transactional
    public void replaceRemote(UUID actorId, Map<UUID, Instant> pinnedAt, Instant fetchedAt) {
        pins.lockActor(actorId);
        var removedAt = pins.removedAt(actorId);
        var listed = repository.findByIds(pinnedAt.keySet()).stream()
                .filter(status -> status.actorId().equals(actorId) && !status.isReblog())
                .filter(status -> {
                    var removed = removedAt.get(status.uri().toString());
                    return removed == null || removed.isBefore(pinnedAt.get(status.id()));
                })
                .sorted(Comparator.comparing((Status status) -> pinnedAt.get(status.id())).reversed())
                .limit(MAX_REMOTE)
                .toList();
        var kept = listed.stream().map(Status::id).toList();
        pins.deleteExcept(actorId, kept, fetchedAt);
        var existing = pins.pinnedAmong(actorId, kept);
        for (var status : listed) {
            if (!existing.contains(status.id())) {
                pins.insert(actorId, status.id(), pinnedAt.get(status.id()), null);
            }
        }
    }

    /**
     * @return which of these statuses the viewer has pinned. Mastodon only says a status is pinned to its author, so only the
     * viewer's own statuses need to be looked up.
     */
    public Set<UUID> pinnedIds(Collection<Status> found, Optional<UUID> viewer) {
        if (viewer.isEmpty()) return Set.of();
        var own = found.stream().filter(status -> status.actorId().equals(viewer.get())).map(Status::id)
                .collect(Collectors.toSet());
        return own.isEmpty() ? Set.of() : pins.pinnedAmong(viewer.get(), own);
    }

    /**
     * @return the account's pinned statuses that the viewer can see, the most recently pinned first
     */
    public List<Status> findPinned(UUID actorId, Optional<UUID> viewer) {
        var ids = pins.pinnedBy(actorId, MAX_REMOTE);
        var visible = viewer.map(id -> statuses.findVisible(ids, id)).orElseGet(() -> statuses.findVisibleToAnyone(ids))
                .stream().collect(Collectors.toMap(Status::id, Function.identity()));
        return ids.stream().map(visible::get).filter(Objects::nonNull).toList();
    }

    public void removeForActor(UUID id) {
        pins.removeForActors(List.of(id));
    }

    public void removeForDomain(String domain) {
        var after = new UUID(0, 0);
        List<UUID> batch;
        do {
            batch = pins.actorsOn(domain, after, 1000);
            if (!batch.isEmpty()) {
                pins.removeForActors(batch);
                after = batch.getLast();
            }
        } while (batch.size() == 1000);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
