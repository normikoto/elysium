package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Acts on a {@code Move} once the account it names says it's also the account that moved, in its {@code alsoKnownAs}, as
 * Mastodon checks. That account's document is fetched again, since it's only just had to list the old one. The old
 * account's own {@code movedTo} isn't needed: servers cache actor documents for a while, so it can still be missing, and
 * the old account's signature already speaks for it.
 */
@Component
class MoveChecks {

    static final TaskDescriptor<MoveCheckJob> TASK = TaskDescriptor.of("move-check", MoveCheckJob.class);

    private static final Logger log = LoggerFactory.getLogger(MoveChecks.class);

    private final SchedulerClient scheduler;
    private final ActorService actors;
    private final ProfileService profiles;
    private final RemoteActors remoteActors;
    private final AccountMigrations migrations;
    private final ActorRefetch refetch;
    private final DomainBlocks blocks;
    private final TargetFetchTurns turns;
    private final InstanceProperties instance;
    private final TransactionTemplate transaction;

    /**
     * @param scheduler lazy, because the scheduler needs every handler's task, and {@link MoveHandler} needs this
     */
    MoveChecks(
            @Lazy SchedulerClient scheduler,
            ActorService actors,
            ProfileService profiles,
            RemoteActors remoteActors,
            AccountMigrations migrations,
            ActorRefetch refetch,
            DomainBlocks blocks,
            TargetFetchTurns turns,
            InstanceProperties instance,
            TransactionTemplate transaction) {
        this.scheduler = scheduler;
        this.actors = actors;
        this.profiles = profiles;
        this.remoteActors = remoteActors;
        this.migrations = migrations;
        this.refetch = refetch;
        this.blocks = blocks;
        this.turns = turns;
        this.instance = instance;
        this.transaction = transaction;
    }

    void queue(String activityId, URI actor, URI target) {
        Jobs.schedule(scheduler, TASK,
                new MoveCheckJob(activityId, actor.toString(), target.toString(),
                        Instant.now().truncatedTo(ChronoUnit.MICROS), null),
                "move-check", activityId);
    }

    /**
     * Fetching the target waits for a turn in {@link TargetFetchTurns}, since whoever sent the {@code Move} chose it.
     */
    CompletionHandler<MoveCheckJob> run(MoveCheckJob job) {
        var target = URI.create(job.targetId());
        if (!isLocal(target)) {
            if (blocks.isBlocked(target.getHost())) {
                return Jobs.done();
            }
            var now = Instant.now();
            var turn = turns.take(target, job.turn(), now);
            if (turn.isEmpty()) {
                log.debug("Ignoring Move {}, since too many fetches are waiting for {}'s server", job.activityId(), target);
                return Jobs.done();
            }
            if (turn.get().isAfter(now)) {
                return Jobs.waitUntil(turn.get(), job.withTurn(turn.get()));
            }
        }
        confirm(job.activityId(), URI.create(job.actorId()), target);
        return Jobs.done();
    }

    /**
     * Has to be called with no transaction open, because it can wait on another server.
     *
     * @throws IllegalStateException if the target's server couldn't be reached, which is worth trying again
     */
    void confirm(String activityId, URI actorUri, URI targetUri) {
        var actor = actors.findByUri(actorUri).filter(Actor::isActive).filter(found -> !actors.isLocal(found));
        if (actor.isEmpty() || migrations.recentlyMoved(actor.get().id())) {
            return;
        }
        var target = isLocal(targetUri) ? actors.findLocalByUri(targetUri) : remoteActors.fetchFresh(targetUri, actorUri);
        if (target.isEmpty()) {
            log.debug("Ignoring Move {}, since {} can't be used", activityId, targetUri);
            return;
        }
        var profile = profiles.find(target.get().id());
        if (!profile.alsoKnownAs().contains(actorUri)) {
            log.debug("Ignoring Move {}, since {} doesn't list {} in alsoKnownAs", activityId, targetUri, actorUri);
            return;
        }
        if (profile.movedTo() != null) {
            log.debug("Ignoring Move {}, since {} has moved too", activityId, targetUri);
            return;
        }
        Optional<UUID> moved = transaction.execute(status -> {
            // Fetching can take a while, and either server can have been blocked, or either account suspended, since.
            if (blocks.isBlocked(actorUri.getHost()) || (!isLocal(targetUri) && blocks.isBlocked(targetUri.getHost()))) {
                return Optional.empty();
            }
            return migrations.recordRemoteMove(actor.get().id(), target.get().id(), activityId);
        });
        if (moved.isPresent()) {
            // So that its document is what's believed again, once its server's cache of it has expired.
            refetch.queue(actorUri, activityId);
        }
    }

    private boolean isLocal(URI uri) {
        return RemoteActorDocument.isOnDomain(uri, instance.domain());
    }
}
