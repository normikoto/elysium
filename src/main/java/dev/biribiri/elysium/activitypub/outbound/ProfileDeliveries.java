package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.activitypub.vocab.Update;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.ProfileUpdated;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells followers' servers when a local account's profile changes.
 *
 * <p>The activity holds the profile as it is when this runs, not as the change left it, so a repeat, or a run after a
 * later change, sends what's true now under the event's ID. Each change has its own ID, and a server that's seen it
 * ignores it. Jobs have no order, so a server can get an older one last, until it next fetches the actor. The actor's
 * {@code updated} says which is newer.
 */
@Service
public class ProfileDeliveries {

    private final ActorService actors;
    private final ProfileService profiles;
    private final FollowService follows;
    private final ActivityDeliveries deliveries;
    private final JsonMapper json;
    private final InstanceProperties instance;

    ProfileDeliveries(
            ActorService actors,
            ProfileService profiles,
            FollowService follows,
            ActivityDeliveries deliveries,
            JsonMapper json,
            InstanceProperties instance) {
        this.actors = actors;
        this.profiles = profiles;
        this.follows = follows;
        this.deliveries = deliveries;
        this.json = json;
        this.instance = instance;
    }

    /**
     * A suspended or deleted account's document no longer shows its profile, and it's told servers that already.
     */
    @ApplicationModuleListener
    public void on(ProfileUpdated event) {
        actors.findLocalById(event.actorId()).ifPresent(actor -> {
            var update = Update.of(Person.from(actor, profiles.find(actor.id()), instance), "profile", event.at());
            deliveries.enqueue(
                    new OutboundActivity(update.id().toString(), actor.id(), json.writeValueAsString(update), WhileSuspended.DROP),
                    follows.followerInboxes(actor.id()));
        });
    }
}
