package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.graph.FollowService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a page offers its viewer for following an account.
 *
 * @param account the account the page is about
 */
record FollowControls(State state, Actor account) {

    enum State {
        /**
         * No one's logged in, so the page asks for the visitor's address instead.
         */
        ANONYMOUS,
        SELF,
        /**
         * Either blocks the other, so there's nothing to offer.
         */
        UNAVAILABLE,
        NOT_FOLLOWING,
        REQUESTED,
        FOLLOWING
    }

    static FollowControls of(Optional<UUID> viewer, Actor account, FollowService follows) {
        if (viewer.isEmpty()) {
            return new FollowControls(State.ANONYMOUS, account);
        }
        if (viewer.get().equals(account.id())) {
            return new FollowControls(State.SELF, account);
        }
        var relationship = follows.relationships(viewer.get(), List.of(account.id())).getFirst();
        if (relationship.blocking() || relationship.blockedBy()) {
            return new FollowControls(State.UNAVAILABLE, account);
        }
        var state = relationship.following() ? State.FOLLOWING
                : relationship.requested() ? State.REQUESTED
                : State.NOT_FOLLOWING;
        return new FollowControls(state, account);
    }

    /**
     * The address as other servers' users write it.
     */
    public String address() {
        return "@" + account.username() + "@" + account.domain();
    }
}
