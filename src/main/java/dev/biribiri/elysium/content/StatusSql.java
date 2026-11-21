package dev.biribiri.elysium.content;

import dev.biribiri.elysium.graph.HiddenAccounts;

import java.util.Map;

/**
 * SQL for choosing statuses, for queries that page through them in their own way, such as timelines, which then load what
 * they found through {@link StatusService}. This module's own queries use these too, so that whatever lists statuses
 * agrees on who can see what.
 *
 * <p>Each condition is on a status joined as {@code s}, and its author as {@code author}, from {@link #FROM_ACTIVE}.
 */
public final class StatusSql {

    /**
     * Only finds statuses of accounts that are neither suspended nor deleted, so that those of a suspended account aren't
     * served by anything, and aren't left to be filtered out after a page of them has been chosen. The same goes for boosts
     * of their statuses. Counts use this too, so that they agree with what's listed.
     */
    public static final String FROM_ACTIVE = "FROM status s "
            + "JOIN actor author ON author.id = s.actor_id AND author.suspended_at IS NULL AND author.deleted_at IS NULL "
            + "AND (s.reblog_of_id IS NULL OR EXISTS (SELECT 1 FROM status o JOIN actor oa ON oa.id = o.actor_id "
            + "WHERE o.id = s.reblog_of_id AND oa.suspended_at IS NULL AND oa.deleted_at IS NULL)) ";

    /**
     * For a query with no viewer, which serves people who aren't logged in and other servers. A viewer is always an account
     * here, so it can see local-only statuses, and the boosts of them, which are local-only too.
     */
    public static final String NOT_LOCAL_ONLY = " AND NOT s.local_only";

    public static final String HAS_MEDIA = " AND EXISTS (SELECT 1 FROM media_attachment m WHERE m.status_id = s.id)";

    /**
     * Excludes replies to other accounts, but keeps an account's replies to itself.
     */
    public static final String NOT_A_REPLY_TO_OTHERS = "(NOT s.reply OR s.in_reply_to_account_id = s.actor_id)";

    private StatusSql() {
    }

    /**
     * Whether the status mentions the viewer.
     *
     * @param viewer the viewer's ID, as SQL
     */
    public static String mentions(String viewer) {
        return " EXISTS (SELECT 1 FROM status_mention m WHERE m.status_id = s.id AND m.actor_id = " + viewer + ")";
    }

    /**
     * Leaves out what's hidden from the viewer: statuses by accounts it has blocked, muted, or blocked the server of, or that
     * block it, and boosts of their statuses.
     *
     * @param viewer the viewer's ID, as SQL
     */
    public static String notHidden(String viewer) {
        return " AND " + HiddenAccounts.visibleInTimelines("author", viewer)
                + " AND (s.reblog_of_id IS NULL OR EXISTS (SELECT 1 FROM status ho JOIN actor hoa ON hoa.id = ho.actor_id"
                + " WHERE ho.id = s.reblog_of_id AND " + HiddenAccounts.visibleInTimelines("hoa", viewer) + "))";
    }

    /**
     * Whether the status has the hashtags that the filter asks for.
     *
     * @param parameters where this binds {@code any}, {@code all}, {@code allCount}, and {@code none}
     */
    public static String taggedWith(TagFilter tags, Map<String, Object> parameters) {
        var filter = new StringBuilder(
                "EXISTS (SELECT 1 FROM status_tag t WHERE t.status_id = s.id AND t.name = ANY (CAST(:any AS text[])))");
        parameters.put("any", tags.any().toArray(String[]::new));
        if (!tags.all().isEmpty()) {
            filter.append("""
                     AND (SELECT count(*) FROM status_tag t WHERE t.status_id = s.id
                          AND t.name = ANY (CAST(:all AS text[]))) = :allCount""");
            parameters.put("all", tags.all().toArray(String[]::new));
            parameters.put("allCount", tags.all().size());
        }
        if (!tags.none().isEmpty()) {
            filter.append("""
                     AND NOT EXISTS (SELECT 1 FROM status_tag t WHERE t.status_id = s.id
                                     AND t.name = ANY (CAST(:none AS text[])))""");
            parameters.put("none", tags.none().toArray(String[]::new));
        }
        return filter.toString();
    }
}
