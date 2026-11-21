package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Delete;
import dev.biribiri.elysium.activitypub.vocab.Follow;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.activitypub.vocab.Undo;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.AccountCleanups;
import dev.biribiri.elysium.actor.AccountDeleted;
import dev.biribiri.elysium.actor.AccountSuspended;
import dev.biribiri.elysium.actor.AccountUnsuspended;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.SentFollow;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tells other servers when a local account is suspended, unsuspended, or deleted, and then queues removing what shouldn't be
 * kept, through {@link AccountCleanups}.
 *
 * <p>This can run again for the same event, which is safe as the jobs are identified by the event, and other servers ignore an
 * {@code id} they've already seen. The events can also run in any order, so each one looks at the account's state now, and only
 * says what's still true. That only covers queuing: an {@code Update}'s jobs have no order, so if a server is down through a
 * quick suspension and its end, the older one can arrive last, until that server next refreshes the actor.
 */
@Service
public class AccountCleanup {

    private final ActorService actors;
    private final FollowService follows;
    private final ActivityDeliveries deliveries;
    private final AccountCleanups cleanups;
    private final JsonMapper json;
    private final InstanceProperties instance;
    private final Persons persons;

    AccountCleanup(
            ActorService actors,
            FollowService follows,
            ActivityDeliveries deliveries,
            AccountCleanups cleanups,
            JsonMapper json,
            InstanceProperties instance,
            Persons persons) {
        this.instance = instance;
        this.persons = persons;
        this.actors = actors;
        this.follows = follows;
        this.deliveries = deliveries;
        this.cleanups = cleanups;
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
            announce(actor.get(), Update.of(Person.suspended(actor.get(), instance), "suspended", event.at()));
        } else {
            cleanups.queue(event.actorId(), event.at().toString());
        }
    }

    @ApplicationModuleListener
    public void on(AccountUnsuspended event) {
        var actor = actors.findById(event.actorId()).filter(Actor::isActive);
        if (actor.isPresent() && actors.isLocal(actor.get())) {
            var person = persons.of(actor.get());
            announce(actor.get(), Update.of(person, "unsuspended", event.at()));
        }
    }

    /**
     * This all happens in one transaction, before the jobs can run, because who to tell comes from the follows they remove.
     * This also cancels any {@code Update} about the account, which can't still be true. Followers get a {@code Delete}, and
     * each account it follows, or asked to, gets an {@code Undo} of the {@code Follow}, as if it had unfollowed, since
     * servers can't all be relied on to stop sending to an actor that's deleted. A remote actor's own server tells everyone
     * else, so nothing is sent for one.
     */
    @ApplicationModuleListener
    public void on(AccountDeleted event) {
        actors.findById(event.actorId()).filter(actors::isLocal).ifPresent(actor -> {
            var delete = Delete.of(actor);
            deliveries.cancelAllBy(actor.id(), delete.id().toString());
            send(actor, delete.id().toString(), json.writeValueAsString(delete));
            withdrawFollows(actor);
        });
        cleanups.queue(event.actorId(), "deleted");
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
}
