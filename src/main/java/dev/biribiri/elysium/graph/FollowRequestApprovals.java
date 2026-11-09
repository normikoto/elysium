package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.FollowerApprovalTurnedOff;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.UUID;

/**
 * Approves the requests still waiting for an account that has stopped approving its followers by hand, as Mastodon does.
 *
 * <p>Each request is approved in its own transaction, so that an account with a lot of them doesn't hold one long one, and
 * a failure part of the way through leaves the rest for the event's retry. Approving only changes requests that are still
 * waiting, so running this again is harmless. If the account starts approving by hand again, the requests not approved yet
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

    // The listener would otherwise hold one transaction around every request it approves.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(FollowerApprovalTurnedOff event) {
        var followedId = event.actorId();
        if (actors.findLocalById(followedId).isEmpty()) {
            return;
        }
        var after = new UUID(0, 0);
        List<UUID> followerIds;
        do {
            followerIds = follows.requesterIds(followedId, after, BATCH_SIZE);
            for (var follower : actors.findByIds(followerIds)) {
                if (!follows.authorizeUnlessLocked(follower, followedId)) {
                    return;
                }
            }
            if (!followerIds.isEmpty()) {
                after = followerIds.getLast();
            }
        } while (followerIds.size() == BATCH_SIZE);
    }
}
