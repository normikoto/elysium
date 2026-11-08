package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Where the activities of statuses and boosts go, so that an activity and the one that withdraws it reach the same servers.
 */
@Component
class Recipients {

    private final ActorService actors;
    private final FollowService follows;

    Recipients(ActorService actors, FollowService follows) {
        this.actors = actors;
        this.follows = follows;
    }

    /**
     * The inboxes of an account's followers for something of this visibility. Someone who follows after something
     * followers-only was posted gets neither it nor its withdrawal, so that the withdrawal can't tell them it existed.
     */
    void addFollowers(UUID actorId, Visibility visibility, Instant publishedAt, Collection<URI> inboxes) {
        switch (visibility) {
            case PUBLIC, UNLISTED -> inboxes.addAll(follows.followerInboxes(actorId));
            case PRIVATE -> inboxes.addAll(follows.followerInboxesAsOf(actorId, publishedAt));
            case DIRECT -> {
            }
        }
    }

    /**
     * Adds an account's inbox if it's an active one on another server.
     */
    void add(Actor actor, Collection<URI> inboxes) {
        if (actor.isActive() && !actors.isLocal(actor)) {
            inboxes.add(actor.sharedInboxUri() == null ? actor.inboxUri() : actor.sharedInboxUri());
        }
    }
}
