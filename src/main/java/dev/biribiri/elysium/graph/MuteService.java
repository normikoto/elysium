package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.graph.internal.MuteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Local accounts' mutes of other accounts, which hide them from timelines, threads and, unless asked not to, notifications.
 * They're never federated, and leave follows as they are. An expired mute is ignored until it's purged.
 */
@Service
public class MuteService {

    private final MuteRepository mutes;

    MuteService(MuteRepository mutes) {
        this.mutes = mutes;
    }

    /**
     * Mutes an account, or changes the mute of one already muted.
     *
     * @param notifications whether to hide its notifications too
     * @param expiresAt     when the mute ends, or empty if it doesn't
     */
    @Transactional
    public void mute(UUID accountId, UUID targetId, boolean notifications, Optional<Instant> expiresAt) {
        if (accountId.equals(targetId)) {
            throw new IllegalArgumentException("An account can't mute itself");
        }
        mutes.save(accountId, targetId, notifications, expiresAt.orElse(null));
    }

    /**
     * @return whether there was a mute
     */
    @Transactional
    public boolean unmute(UUID accountId, UUID targetId) {
        return mutes.delete(accountId, targetId);
    }

    /**
     * Lists the accounts an account has muted, newest first, leaving out expired mutes and suspended and deleted accounts.
     */
    public List<FollowEntry> muted(UUID accountId, FollowPage page) {
        return mutes.findPage(accountId, page);
    }

    /**
     * Removes the mutes an actor made and those of it.
     */
    public void removeForActor(UUID actorId) {
        mutes.deleteForActor(actorId);
    }

    /**
     * @return how many expired mutes were removed
     */
    public int removeExpired() {
        return mutes.deleteExpired();
    }
}
