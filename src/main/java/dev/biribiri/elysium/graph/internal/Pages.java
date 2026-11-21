package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.actor.ActorSql;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.util.HashMap;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/**
 * Pages through a local account's blocks, mutes, or list members, newest first, in the same way as follows: by when each
 * was made, since their IDs are random.
 */
final class Pages {

    private static final RowMapper<FollowEntry> MAPPER = (rs, row) -> new FollowEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("other_id", UUID.class),
            URI.create(rs.getString("other_uri")));

    private Pages() {
    }

    /**
     * @param table a table with {@code id}, {@code account_id}, {@code created_at}, and a column naming the other account
     * @param extra more conditions on the row, joined as {@code r}, or an empty string
     */
    static List<FollowEntry> find(JdbcClient jdbc, String table, String extra, UUID accountId, FollowPage page) {
        return find(jdbc, table, extra, Map.of(), accountId, page);
    }

    /**
     * @param extraParameters what {@code extra} binds
     */
    static List<FollowEntry> find(JdbcClient jdbc, String table, String extra, Map<String, ?> extraParameters,
                                  UUID accountId, FollowPage page) {
        var sql = new StringBuilder(select(table, extra));
        var parameters = new HashMap<String, Object>();
        parameters.putAll(extraParameters);
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
        var found = jdbc.sql(sql.toString()).params(parameters).query(MAPPER).list();
        return ascending ? found.reversed() : found;
    }

    /**
     * Like {@link #find}, every row at once.
     */
    static List<FollowEntry> findAll(JdbcClient jdbc, String table, String extra, Map<String, ?> extraParameters,
                                     UUID accountId) {
        var parameters = new HashMap<String, Object>(extraParameters);
        parameters.put("account", accountId);
        return jdbc.sql(select(table, extra) + " ORDER BY r.created_at DESC, r.id DESC")
                .params(parameters)
                .query(MAPPER)
                .list();
    }

    private static String select(String table, String extra) {
        return "SELECT r.id, a.id AS other_id, a.uri AS other_uri FROM " + table
                + " r JOIN actor a ON a.id = r.target_id WHERE r.account_id = :account"
                + " AND " + ActorSql.active("a") + extra;
    }
}
