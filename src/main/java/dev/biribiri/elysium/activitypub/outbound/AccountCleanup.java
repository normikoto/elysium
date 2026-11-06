package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.vocab.Delete;
import dev.biribiri.elysium.activitypub.vocab.Follow;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.activitypub.vocab.Undo;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.AccountDeleted;
import dev.biribiri.elysium.actor.AccountSuspended;
import dev.biribiri.elysium.actor.AccountUnsuspended;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.SentFollow;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tells other servers when a local account is suspended, unsuspended, or deleted, and queues removing what shouldn't be kept.
 * Removal is a job because there can be a lot to remove.
 *
 * <p>This can run again for the same event, which is safe as the jobs are identified by the event, and other servers ignore an
 * {@code id} they've already seen. The events can also run in any order, so each one looks at the account's state now, and only
 * says what's still true. That only covers queuing: an {@code Update}'s jobs have no order, so if a server is down through a
 * quick suspension and its end, the older one can arrive last, until that server next refreshes the actor.
 */
@Service
public class AccountCleanup {

    static final TaskDescriptor<AccountCleanupJob> TASK = TaskDescriptor.of("account-cleanup", AccountCleanupJob.class);

    /**
     * A {@code Follow} or a status that was checked just before the change committed can be saved after the first job has run.
     */
    private static final Duration SECOND_SWEEP_DELAY = Duration.ofMinutes(5);

    private final ActorService actors;
    private final FollowService follows;
    private final ActivityDeliveries deliveries;
    private final SchedulerClient scheduler;
    private final JsonMapper json;
    private final InstanceProperties instance;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    AccountCleanup(
            ActorService actors,
            FollowService follows,
            ActivityDeliveries deliveries,
            @Lazy SchedulerClient scheduler,
            JsonMapper json,
            InstanceProperties instance) {
        this.instance = instance;
        this.actors = actors;
        this.follows = follows;
        this.deliveries = deliveries;
        this.scheduler = scheduler;
        this.json = json;
    }

    /**
     * A suspended local account keeps its followers, who are told so that they can stop showing it. A remote one loses its
     * follows, and its server isn't told anything.
     */
    @ApplicationModuleListener
    public void on(AccountSuspended event) {
        var actor = actors.findById(event.actorId()).filter(found -> found.isSuspended() && !found.isDeleted());
        if (actor.isEmpty()) {
            return;
        }
        if (actors.isLocal(actor.get())) {
            announce(actor.get(), Update.of(Person.suspended(actor.get(), instance.sharedInboxUri()), "suspended", event.at()));
        } else {
            sweep(event.actorId(), event.at().toString());
        }
    }

    @ApplicationModuleListener
    public void on(AccountUnsuspended event) {
        var actor = actors.findById(event.actorId()).filter(Actor::isActive);
        if (actor.isPresent() && actors.isLocal(actor.get())) {
            announce(actor.get(), Update.of(Person.from(actor.get(), instance.sharedInboxUri()), "unsuspended", event.at()));
        }
    }

    /**
     * This all happens in one transaction, before the jobs can run, because who to tell comes from the follows they remove.
     * This also cancels any {@code Update} about the account, which can't still be true. Followers get a {@code Delete}, and
     * each account it follows, or asked to, gets an {@code Undo} of the {@code Follow}, as if it had unfollowed, since
     * servers can't all be relied on to stop sending to an actor that's deleted.
     */
    @ApplicationModuleListener
    public void on(AccountDeleted event) {
        actors.findById(event.actorId()).ifPresent(actor -> {
            var delete = Delete.of(actor);
            deliveries.cancelAllBy(actor.id(), delete.id().toString());
            send(actor, delete.id().toString(), json.writeValueAsString(delete));
            withdrawFollows(actor);
        });
        sweep(event.actorId(), "deleted");
    }

    /**
     * Each {@code Undo} waits for any request still sending its {@code Follow}, as unfollowing does.
     */
    private void withdrawFollows(Actor actor) {
        var sent = follows.sentBy(actor.id());
        var followed = actors.findByIds(sent.stream().map(SentFollow::followedId).toList()).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        for (var follow : sent) {
            var target = followed.get(follow.followedId());
            if (target == null) {
                continue;
            }
            var undo = Undo.of(Follow.of(follow.activityUri(), actor.uri(), target.uri()));
            deliveries.enqueueAfter(
                    new OutboundActivity(undo.id(), actor.id(), json.writeValueAsString(undo), WhileSuspended.SEND),
                    List.of(target.inboxUri()),
                    follow.activityUri());
        }
    }

    private void announce(Actor actor, Update update) {
        send(actor, update.id().toString(), json.writeValueAsString(update));
    }

    private void send(Actor actor, String id, String body) {
        deliveries.enqueue(new OutboundActivity(id, actor.id(), body, WhileSuspended.SEND), follows.followerInboxes(actor.id()));
    }

    private void sweep(UUID actorId, String occasion) {
        var now = Instant.now();
        var job = new AccountCleanupJob(actorId, now);
        Jobs.schedule(scheduler, TASK, job, actorId.toString(), occasion, "first");
        Jobs.scheduleAt(scheduler, TASK, job, now.plus(SECOND_SWEEP_DELAY), actorId.toString(), occasion, "second");
    }
}
