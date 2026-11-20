package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.content.internal.ThreadMuteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mastodon's muted conversations: threads a local account no longer wants notifications from. A mute covers the whole
 * thread, including parts of it that join later, and only the account that made it ever learns of it.
 */
@Service
public class ThreadMuteService {
    private final ThreadMuteRepository mutes;
    private final StatusRepository repository;
    private final StatusService statuses;
    private final ActorService actors;

    ThreadMuteService(ThreadMuteRepository mutes, StatusRepository repository, StatusService statuses,
                      ActorService actors) {
        this.mutes = mutes;
        this.repository = repository;
        this.statuses = statuses;
        this.actors = actors;
    }

    /**
     * @return the status, or empty if the account can't see it
     */
    @Transactional
    public Optional<Status> mute(UUID statusId, UUID actorId) {
        if (actors.findLocalById(actorId).isEmpty()) return Optional.empty();
        // Taken first, as merging threads takes it, so that the status can't move to another thread before the insert.
        repository.lockIdAssignment();
        var found = statuses.findVisible(statusId, Optional.of(actorId));
        found.ifPresent(status -> mutes.insert(actorId, status.id()));
        return found;
    }

    /**
     * Unmutes even when the account can no longer see the status, as bookmarks are removed, since the mute would
     * otherwise stay with no way to clear it.
     *
     * @return the status, or empty if the account can't see it
     */
    @Transactional
    public Optional<Status> unmute(UUID statusId, UUID actorId) {
        if (actors.findLocalById(actorId).isEmpty()) return Optional.empty();
        repository.lockIdAssignment();
        mutes.delete(actorId, statusId);
        return statuses.findVisible(statusId, Optional.of(actorId));
    }

    public boolean isMuted(UUID actorId, UUID statusId) {
        return mutes.isMuted(actorId, statusId);
    }

    public Set<UUID> mutedIds(Collection<UUID> statusIds, Optional<UUID> viewer) {
        return viewer.map(id -> mutes.mutedAmong(id, statusIds)).orElse(Set.of());
    }

    public void removeForActor(UUID actorId) {
        mutes.removeForActor(actorId);
    }
}
