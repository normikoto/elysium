package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.PostCleanupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Deletes local accounts' old statuses and boosts, as their {@link PostCleanupPolicy} says.
 *
 * <p>Each deletion reaches every server that was sent the status, so a run only deletes a few of each account's, and
 * only looks at so many accounts, as Mastodon does. An account that's just turned its policy on works through its old
 * statuses over many runs, rather than sending thousands of {@code Delete}s at once.
 */
@Service
public class PostCleanups {

    private static final Logger log = LoggerFactory.getLogger(PostCleanups.class);

    static final int PER_ACCOUNT = 50;
    static final int ACCOUNTS_PER_RUN = 100;

    private final PostCleanupRepository repository;
    private final StatusService statuses;
    private final BoostService boosts;
    private final ActorService actors;

    PostCleanups(PostCleanupRepository repository, StatusService statuses, BoostService boosts, ActorService actors) {
        this.repository = repository;
        this.statuses = statuses;
        this.boosts = boosts;
        this.actors = actors;
    }

    public PostCleanupPolicy find(UUID actorId) {
        return repository.find(actorId).orElse(PostCleanupPolicy.DEFAULT);
    }

    public void save(UUID actorId, PostCleanupPolicy policy) {
        repository.save(actorId, policy);
    }

    public void removeFor(UUID actorId) {
        repository.remove(actorId);
    }

    /**
     * @return how many statuses and boosts were deleted
     */
    public int runDue() {
        return run(Instant.now());
    }

    /**
     * Like {@link #runDue}, as if it were {@code now}, so that tests needn't wait for statuses to grow old.
     */
    public int run(Instant now) {
        var deleted = 0;
        for (var actorId : repository.findEnabled(ACCOUNTS_PER_RUN)) {
            try {
                deleted += runFor(actorId, now);
            } catch (RuntimeException e) {
                // One account's failure mustn't stop the others' turns, which marking it run keeps going.
                log.warn("Couldn't delete the old statuses of {}", actorId, e);
            }
            repository.markRun(actorId, now);
        }
        return deleted;
    }

    /**
     * A suspended account's statuses wait, since it can be unsuspended, and a policy changed meanwhile is read again.
     */
    private int runFor(UUID actorId, Instant now) {
        if (actors.findLocalById(actorId).isEmpty()) {
            return 0;
        }
        var policy = repository.find(actorId).filter(PostCleanupPolicy::enabled);
        if (policy.isEmpty()) {
            return 0;
        }
        var before = now.minus(policy.get().minimumAge().duration());
        var deleted = 0;
        for (var candidate : repository.findDeletable(actorId, policy.get(), before, PER_ACCOUNT)) {
            // There's no redrafting a status nobody chose to delete, so its attachments go with it.
            var gone = candidate.boost()
                    ? boosts.removeOwn(candidate.id(), actorId)
                    : statuses.delete(candidate.id(), actorId, true).isPresent();
            if (gone) {
                deleted++;
            }
        }
        return deleted;
    }
}
