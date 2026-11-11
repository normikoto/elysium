package dev.biribiri.elysium.graph;

/**
 * A follow that a block forbids: one of the two accounts blocks the other, or the follower has blocked the other's server.
 */
public class FollowNotAllowedException extends RuntimeException {

    public FollowNotAllowedException() {
        super("A block stops this account from following that one");
    }
}
