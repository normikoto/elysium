package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowMoves;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

/**
 * Moves local followers of an account that moved, and carries over local accounts' blocks and mutes of it, through
 * {@link FollowMoves}. A follower that's been moved no longer follows the old account, so a repeat only does what's left.
 * Nothing is moved to an account that's since been suspended or deleted, or whose server has been blocked, and an old
 * account that's been suspended or deleted has lost its followers already.
 */
@Configuration(proxyBeanMethods = false)
class FollowerMoveTask {

    private final AccountMigrations migrations;
    private final ActorService actors;
    private final FollowService follows;
    private final FollowMoves moves;
    private final DomainBlocks domainBlocks;

    FollowerMoveTask(
            AccountMigrations migrations,
            ActorService actors,
            FollowService follows,
            FollowMoves moves,
            DomainBlocks domainBlocks) {
        this.migrations = migrations;
        this.actors = actors;
        this.follows = follows;
        this.moves = moves;
        this.domainBlocks = domainBlocks;
    }

    @Bean
    Task<FollowerMoveJob> moveFollowers() {
        return Jobs.task(AccountMoves.TASK, this::run);
    }

    private CompletionHandler<FollowerMoveJob> run(FollowerMoveJob job) {
        migrations.find(job.moveId()).ifPresent(move -> {
            var from = usable(move.actorId());
            var to = usable(move.targetId());
            if (from.isEmpty() || to.isEmpty()) {
                return;
            }
            for (var followerId : follows.localFollowerIds(from.get().uri())) {
                actors.findLocalById(followerId)
                        .ifPresent(follower -> moves.moveFollower(move.id(), follower, from.get(), to.get()));
            }
            moves.carryBlocks(from.get(), to.get());
            moves.carryMutes(from.get().id(), to.get().id());
        });
        return Jobs.done();
    }

    private Optional<Actor> usable(UUID actorId) {
        return actors.findById(actorId)
                .filter(Actor::isActive)
                .filter(actor -> actors.isLocal(actor) || !domainBlocks.isBlocked(actor.uri().getHost()));
    }
}
