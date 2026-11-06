package dev.biribiri.elysium.graph;

/**
 * How many accepted follows an account has in each direction, as far as this server knows. For a remote account, that's only
 * follows to and from local accounts.
 */
public record FollowCounts(long followers, long following) {

    public static final FollowCounts NONE = new FollowCounts(0, 0);
}
