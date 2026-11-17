package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.vocab.Move;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.AccountMoved;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Acts on an account's move, wherever it and the account it moved to are. A local account's followers' servers are sent
 * an {@code Update} with its {@code movedTo}, and a {@code Move}, so that they can move its followers there. Its followers
 * here, and those of an account on another server, are moved by a job, since there can be a lot of them.
 *
 * <p>The {@code Update} comes first, though jobs have no order, as Mastodon checks the new account's {@code alsoKnownAs}
 * rather than the old one's {@code movedTo}. Each activity's ID and the job's come from the move, so a repeat queues
 * nothing new. A local account that's stopped being moved by the time this runs isn't announced as moved, but its local
 * followers are still moved, as they would have been had this run first.
 */
@Service
public class AccountMoves {

    static final TaskDescriptor<FollowerMoveJob> TASK = TaskDescriptor.of("follower-move", FollowerMoveJob.class);

    private final ActorService actors;
    private final AccountMigrations migrations;
    private final Persons persons;
    private final FollowService follows;
    private final ActivityDeliveries deliveries;
    private final SchedulerClient scheduler;
    private final JsonMapper json;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    AccountMoves(
            ActorService actors,
            AccountMigrations migrations,
            Persons persons,
            FollowService follows,
            ActivityDeliveries deliveries,
            @Lazy SchedulerClient scheduler,
            JsonMapper json) {
        this.actors = actors;
        this.migrations = migrations;
        this.persons = persons;
        this.follows = follows;
        this.deliveries = deliveries;
        this.scheduler = scheduler;
        this.json = json;
    }

    @ApplicationModuleListener
    public void on(AccountMoved event) {
        // A target that's been suspended or deleted since is no longer worth moving to, as FollowerMoveTask also decides.
        var target = actors.findById(event.targetId()).filter(Actor::isActive);
        if (target.isEmpty()) {
            return;
        }
        actors.findLocalById(event.actorId())
                .filter(actor -> migrations.isMovedTo(actor.id(), target.get().uri()))
                .ifPresent(actor -> {
                    var inboxes = follows.followerInboxes(actor.id());
                    var update = Update.of(persons.of(actor), "moved", event.at());
                    deliveries.enqueue(
                            new OutboundActivity(update.id().toString(), actor.id(), json.writeValueAsString(update),
                                    WhileSuspended.DROP),
                            inboxes);
                    var move = Move.of(actor, target.get().uri(), event.at());
                    deliveries.enqueue(
                            new OutboundActivity(move.id().toString(), actor.id(), json.writeValueAsString(move),
                                    WhileSuspended.DROP),
                            inboxes);
                });
        Jobs.schedule(scheduler, TASK, new FollowerMoveJob(event.moveId(), Instant.now()), event.moveId().toString());
    }
}
