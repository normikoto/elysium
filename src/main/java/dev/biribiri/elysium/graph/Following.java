package dev.biribiri.elysium.graph;

/**
 * An SQL condition for one account following another, for other modules' queries that have to check it for many accounts
 * at once rather than read each one's follows first. A request that hasn't been accepted doesn't count, as with
 * {@link FollowService#followedIds}.
 */
public final class Following {

    private Following() {
    }

    /**
     * @param follower the follower's ID, as SQL
     * @param followed the followed account's ID, as SQL, which can be null for no one
     */
    public static String accepted(String follower, String followed) {
        return "EXISTS (SELECT 1 FROM follow hf WHERE hf.follower_id = " + follower + " AND hf.followed_id = " + followed
                + " AND hf.state = 'accepted')";
    }
}
