package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.util.HashMap;
import java.util.UUID;
import java.util.List;

/**
 * Pages through a local account's list of blocks or mutes, newest first, in the same way as follows: by when each was made,
 * since their IDs are random.
 */
final class Pages {

    private Pages() {
    }

    /**
     * @param table a table with {@code id}, {@code account_id}, {@code created_at}, and a column naming the other account
     * @param extra more conditions on the row, joined as {@code r}, or an empty string
     */
    static List<FollowEntry> find(JdbcClient jdbc, String table, String extra, UUID accountId, FollowPage page) {
        var sql = new StringBuilder("SELECT r.id, a.id AS other_id, a.uri AS other_uri FROM ").append(table)
                .append(" r JOIN actor a ON a.id = r.target_id WHERE r.account_id = :account")
                .append(" AND a.suspended_at IS NULL AND a.deleted_at IS NULL").append(extra);
        var parameters = new HashMap<String, Object>();
        parameters.put("account", accountId);
        var cursor = " (SELECT created_at, id FROM " + table + " WHERE id = ";
        if (page.maxId() != null) {
            sql.append(" AND (r.created_at, r.id) <").append(cursor).append(":maxId)");
            parameters.put("maxId", page.maxId());
        }
        var ascending = page.minId() != null;
        if (ascending) {
            sql.append(" AND (r.created_at, r.id) >").append(cursor).append(":minId)");
            parameters.put("minId", page.minId());
        } else if (page.sinceId() != null) {
            sql.append(" AND (r.created_at, r.id) >").append(cursor).append(":sinceId)");
            parameters.put("sinceId", page.sinceId());
        }
        sql.append(ascending ? " ORDER BY r.created_at ASC, r.id ASC" : " ORDER BY r.created_at DESC, r.id DESC")
                .append(" LIMIT :limit");
        parameters.put("limit", page.limit());
        var found = jdbc.sql(sql.toString())
                .params(parameters)
                .query((rs, row) -> new FollowEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("other_id", UUID.class),
                        URI.create(rs.getString("other_uri"))))
                .list();
        return ascending ? found.reversed() : found;
    }
}
