package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Works out which local accounts an activity is for, from the activity itself rather than the inbox it was sent to, because a
 * shared inbox delivery doesn't say. Handlers that store something for its recipients should use this.
 *
 * <p>A local account is a recipient if it's addressed directly, in any of {@code to}, {@code cc}, {@code bto}, {@code bcc},
 * or {@code audience}, of the activity or its embedded object, if it follows the activity's actor and that actor's followers
 * collection is addressed, or if it's the {@code object} of a {@code Follow}. Suspended and deleted accounts are left out.
 * This only reads what's stored, so it never makes a request, and it can't see collections of any other actor, such as the
 * followers of someone being replied to.
 */
@Component
class LocalRecipients {

    private final ActorService actors;
    private final FollowService follows;
    private final InstanceProperties instance;
    private final JsonMapper json;

    LocalRecipients(ActorService actors, FollowService follows, InstanceProperties instance, JsonMapper json) {
        this.actors = actors;
        this.follows = follows;
        this.instance = instance;
        this.json = json;
    }

    /**
     * @return the IDs of the local actors that the activity is for
     */
    Set<UUID> of(InboundActivity activity) {
        var addressed = Addressing.ofActivity(json.readTree(activity.body())).all();
        var local = new ArrayList<URI>();
        addressed.forEach(address -> onThisServer(address).ifPresent(local::add));
        if (activity.type().equals("Follow")) {
            ActivityObject.of(activity.body(), json)
                    .map(ActivityObject::id)
                    .flatMap(this::onThisServer)
                    .ifPresent(local::add);
        }

        var recipients = new HashSet<UUID>();
        actors.findLocalByUris(local).stream().map(Actor::id).forEach(recipients::add);
        actors.findByUri(URI.create(activity.actorUri()))
                .filter(sender -> !actors.isLocal(sender))
                .filter(sender -> actors.findFollowersUri(sender.id())
                        .map(followers -> addressed.contains(followers.toString()))
                        .orElse(false))
                .ifPresent(sender -> recipients.addAll(follows.localFollowerIds(sender.id())));
        return recipients;
    }

    /**
     * Only addresses on this server are looked up, which also leaves out the public collection.
     */
    private Optional<URI> onThisServer(String address) {
        return ActivityObject.parseUri(address)
                .filter(uri -> uri.getHost() != null)
                .filter(uri -> HostHeader.value(uri.getScheme(), uri.getHost(), uri.getPort()).equals(instance.domain()));
    }
}
