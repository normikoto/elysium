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

    /**
     * That the follower's follow of a status's author keeps the status out of the follower's home and list feeds: a boost
     * when the follow hides boosts, or a status in a language the follow leaves out. A boost has no language of its own,
     * so the boosted status's counts, and a status whose language isn't known is never left out, as on Mastodon. Codes are
     * compared ignoring case, and a chosen {@code pt} lets in {@code pt-BR}, since statuses keep their codes as they're
     * sent, while clients offer primary languages.
     *
     * @param follower the follower's ID, as SQL
     * @param status   the alias of the {@code status} row
     */
    public static String hidesFromFeeds(String follower, String status) {
        return "EXISTS (SELECT 1 FROM follow ff WHERE ff.follower_id = " + follower + " AND ff.followed_id = " + status
                + ".actor_id AND ((NOT ff.show_reblogs AND " + status + ".reblog_of_id IS NOT NULL)"
                + " OR EXISTS (SELECT 1 FROM (SELECT lower(COALESCE(" + status + ".language,"
                + " (SELECT fo.language FROM status fo WHERE fo.id = " + status + ".reblog_of_id))) AS code) fl"
                + " WHERE fl.code IS NOT NULL AND ff.languages IS NOT NULL AND NOT EXISTS (SELECT 1 FROM"
                + " unnest(ff.languages) AS chosen (code) WHERE lower(chosen.code) IN (fl.code, split_part(fl.code, '-', 1))))))";
    }
}
