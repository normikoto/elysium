package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.LikeRepository;
import dev.biribiri.elysium.content.internal.StatusRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What likes and reactions share: how they hold a status they act on, and how they find a server's actors to remove what
 * they did. Locks are taken in the order cascading deletion takes them: actors, then the status.
 */
@Component
class StatusHolds {

    private static final int BATCH_SIZE = 1000;

    private final LikeRepository likes;
    private final StatusRepository repository;
    private final StatusService statuses;

    StatusHolds(LikeRepository likes, StatusRepository repository, StatusService statuses) {
        this.likes = likes;
        this.repository = repository;
        this.statuses = statuses;
    }

    /**
     * Holds a status the actor can see, and both actors, until the transaction ends.
     *
     * @return empty if either actor can't be shown, or the actor can't see the status
     */
    Optional<Status> holdVisible(UUID statusId, UUID actorId) {
        var preview = repository.findById(statusId);
        if (preview.isEmpty()) return Optional.empty();
        likes.holdActors(List.of(actorId, preview.get().actorId()));
        if (!likes.eligibleActor(actorId) || !likes.eligibleActor(preview.get().actorId())) return Optional.empty();
        return repository.findByIdAndLock(statusId).filter(status -> statuses.isVisible(status, Optional.of(actorId)));
    }

    /**
     * Hands each batch of a server's actors to {@code remove}.
     */
    void forActorsOn(String domain, Consumer<List<UUID>> remove) {
        var after = new UUID(0, 0);
        List<UUID> batch;
        do {
            batch = likes.actorsOn(domain, after, BATCH_SIZE);
            if (!batch.isEmpty()) {
                remove.accept(batch);
                after = batch.getLast();
            }
        } while (batch.size() == BATCH_SIZE);
    }
}
