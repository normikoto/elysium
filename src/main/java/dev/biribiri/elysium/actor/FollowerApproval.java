package dev.biribiri.elysium.actor;

/**
 * How a local account decides on requests to follow it.
 *
 * @param byHand         whether requests wait for the account's approval
 * @param exceptFollowed whether requests from accounts it follows are approved straight away, which only matters when
 *                       {@code byHand} is true
 */
public record FollowerApproval(boolean byHand, boolean exceptFollowed) {

    public static final FollowerApproval NONE = new FollowerApproval(false, false);
}
