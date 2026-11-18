package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.FollowedApprovalTurnedOn;
import dev.biribiri.elysium.actor.FollowerApprovalTurnedOff;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Approves the requests still waiting for an account that has stopped approving its followers by hand, as Mastodon does,
 * or that has started approving requests from the accounts it follows straight away.
 *
 * <p>Each request is approved in its own transaction, so that an account with a lot of them doesn't hold one long one, and
 * a failure part of the way through leaves the rest for the event's retry. Approving only changes requests that are still
 * waiting, so running this again is harmless. If the account changes the setting back, the requests not approved yet
 * are left for it, which each approval checks for under the same locks as a new request.
 */
@Component
class FollowRequestApprovals {

    private static final int BATCH_SIZE = 1000;

    private final FollowService follows;
    private final ActorService actors;

    FollowRequestApprovals(FollowService follows, ActorService actors) {
        this.follows = follows;
        this.actors = actors;
    }

    // The listeners would otherwise hold one transaction around every request they approve.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(FollowerApprovalTurnedOff event) {
        approve(event.actorId(), follows::authorizeUnlessLocked);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(FollowedApprovalTurnedOn event) {
        approve(event.actorId(), follows::authorizeIfFollowed);
    }

    /**
     * @param approval approves a request if it should be, and returns false once the setting has changed back
     */
    private void approve(UUID followedId, BiPredicate<Actor, UUID> approval) {
        if (actors.findLocalById(followedId).isEmpty()) {
            return;
        }
        var after = new UUID(0, 0);
        List<UUID> followerIds;
        do {
            followerIds = follows.requesterIds(followedId, after, BATCH_SIZE);
            for (var follower : actors.findByIds(followerIds)) {
                if (!approval.test(follower, followedId)) {
                    return;
                }
            }
            if (!followerIds.isEmpty()) {
                after = followerIds.getLast();
            }
        } while (followerIds.size() == BATCH_SIZE);
    }
}
