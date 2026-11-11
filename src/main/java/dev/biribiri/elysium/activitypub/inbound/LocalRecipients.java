package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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
 * or {@code audience}, of the activity or of the object embedded in a {@code Create} or {@code Update}, if it follows the activity's actor and that actor's followers
 * collection is addressed, or if it's the {@code object} of a {@code Follow}. Suspended and deleted accounts are left out,
 * as are those that block the activity's actor or its server, for whom nothing it sends is stored.
 * This only reads what's stored, so it never makes a request, and it can't see collections of any other actor, such as the
 * followers of someone being replied to. {@link #repliesToSomeoneHere} covers replies, for a caller that keeps them.
 */
@Component
class LocalRecipients {

    private final ActorService actors;
    private final FollowService follows;
    private final BlockService blocks;
    private final StatusService statuses;
    private final InstanceProperties instance;
    private final JsonMapper json;

    LocalRecipients(
            ActorService actors, FollowService follows, BlockService blocks, StatusService statuses,
            InstanceProperties instance, JsonMapper json) {
        this.actors = actors;
        this.follows = follows;
        this.blocks = blocks;
        this.statuses = statuses;
        this.instance = instance;
        this.json = json;
    }

    /**
     * @return the IDs of the local actors that the activity is for
     */
    Set<UUID> of(InboundActivity activity) {
        return of(activity, Addressing.NONE);
    }

    /**
     * @param object the addressing of an object that was fetched, because the activity only gave its ID
     * @return the IDs of the local actors that the activity or that object is for
     */
    Set<UUID> of(InboundActivity activity, Addressing object) {
        return of(activity, json.readTree(activity.body()), object);
    }

    /**
     * @param document the activity's JSON, for a caller that has already parsed it
     */
    Set<UUID> of(InboundActivity activity, JsonNode document, Addressing object) {
        var addressed = Addressing.ofActivity(document).with(object).all();
        var local = new ArrayList<URI>();
        addressed.forEach(address -> onThisServer(address).ifPresent(local::add));
        if (activity.type().equals("Follow")) {
            ActivityObject.of(document)
                    .map(ActivityObject::id)
                    .flatMap(this::onThisServer)
                    .ifPresent(local::add);
        }

        var recipients = new HashSet<UUID>();
        actors.findLocalByUris(local).stream().map(Actor::id).forEach(recipients::add);
        recipients.addAll(followersOf(activity, addressed));
        recipients.removeAll(blocks.refusingSender(recipients, URI.create(activity.actorUri())));
        return recipients;
    }

    /**
     * @return the IDs of the local actors that follow the activity's actor, if it's addressed to that actor's followers,
     * leaving out those it only names
     */
    Set<UUID> followersOf(InboundActivity activity, JsonNode document) {
        return followersOf(activity, Addressing.ofActivity(document).all());
    }

    private Set<UUID> followersOf(InboundActivity activity, Set<String> addressed) {
        var sender = URI.create(activity.actorUri());
        if (actors.findFollowersUri(sender).filter(followers -> addressed.contains(followers.toString())).isPresent()) {
            return Set.copyOf(follows.localFollowerIds(sender));
        }
        return Set.of();
    }

    /**
     * @return the IDs of the local actors named one by one in an object's addressing, leaving out those addressed only as
     * followers
     */
    Set<UUID> named(Addressing object) {
        var local = new ArrayList<URI>();
        object.all().forEach(address -> onThisServer(address).ifPresent(local::add));
        var named = new HashSet<UUID>();
        actors.findLocalByUris(local).stream().map(Actor::id).forEach(named::add);
        return named;
    }

    /**
     * Whether a reply concerns someone here, whether or not it addresses them: the status it replies to is stored, and its
     * author is local or followed by a local account. This is Mastodon's rule for replies, and it's what lets a reply
     * forwarded by the parent's server be kept, since the reply only addresses its own author's followers.
     */
    boolean repliesToSomeoneHere(URI parentUri) {
        return statuses.findAuthorOf(parentUri)
                .filter(author -> RemoteActorDocument.isOnDomain(author, instance.domain()) || follows.hasLocalFollowers(author))
                .isPresent();
    }

    /**
     * Only addresses on this server are looked up, which also leaves out the public collection.
     */
    private Optional<URI> onThisServer(String address) {
        return ActivityObject.parseUri(address).filter(uri -> RemoteActorDocument.isOnDomain(uri, instance.domain()));
    }
}
