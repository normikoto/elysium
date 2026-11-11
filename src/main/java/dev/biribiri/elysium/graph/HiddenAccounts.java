package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.graph.internal.GraphSql;

/**
 * SQL conditions for leaving out what a viewer has blocked, muted or blocked the server of, and what's by accounts that
 * have blocked the viewer. Other modules filter in their own queries, before paging or counting, so that a page isn't
 * emptied after it's been chosen. This is the one copy of the rules, which other modules' SQL shouldn't repeat.
 *
 * <p>Each condition reads the {@code :viewer} parameter, which the caller binds to a local account.
 */
public final class HiddenAccounts {

    private HiddenAccounts() {
    }

    /**
     * Whether the actor joined as {@code actor} isn't hidden from the viewer's timelines and threads: neither blocks the
     * other, the viewer hasn't muted it, and the viewer hasn't blocked its server.
     */
    public static String visibleInTimelines(String actor) {
        return visible(actor + ".id", actor, "");
    }

    /**
     * Like {@link #visibleInTimelines}, except that a mute only counts if it also hides notifications.
     */
    public static String visibleInNotifications(String actor) {
        return visible(actor + ".id", actor, " AND hm.notifications");
    }

    /**
     * Like {@link #visibleInTimelines}, for an actor known only by its ID, which can be null for no one.
     */
    public static String visibleInTimelinesById(String actorId) {
        return "(" + actorId + " IS NULL OR NOT EXISTS (SELECT 1 FROM actor ha WHERE ha.id = " + actorId
                + " AND NOT " + visible("ha.id", "ha", "") + "))";
    }

    /**
     * Why the actor joined as {@code actor} is hidden from the viewer by the viewer's own choice, as {@code 'blocked'},
     * {@code 'domain_blocked'} or {@code 'muted'}, or null if it isn't. An actor that blocks the viewer isn't covered,
     * since what it posts can't be seen at all.
     */
    static String hidingReason(String actor) {
        var id = actor + ".id";
        return "CASE WHEN EXISTS (SELECT 1 FROM account_block hb WHERE hb.account_id = :viewer AND hb.target_id = " + id
                + ") THEN 'blocked' WHEN " + GraphSql.domainBlocked(":viewer", actor) + " THEN 'domain_blocked'"
                + " WHEN EXISTS (SELECT 1 FROM account_mute hm WHERE hm.account_id = :viewer AND hm.target_id = " + id
                + " AND (hm.expires_at IS NULL OR hm.expires_at > now())) THEN 'muted' END";
    }

    private static String visible(String id, String actor, String muteCondition) {
        return "(NOT EXISTS (SELECT 1 FROM account_block hb WHERE hb.account_id = :viewer AND hb.target_id = " + id + ")"
                + " AND NOT EXISTS (SELECT 1 FROM account_block hb WHERE hb.account_id = " + id + " AND hb.target_id = :viewer)"
                + " AND NOT EXISTS (SELECT 1 FROM account_mute hm WHERE hm.account_id = :viewer AND hm.target_id = " + id
                + " AND (hm.expires_at IS NULL OR hm.expires_at > now())" + muteCondition + ")"
                + " AND NOT " + GraphSql.domainBlocked(":viewer", actor) + ")";
    }
}
