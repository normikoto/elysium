package dev.biribiri.elysium.content.internal;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pages through rows by a time-ordered ID column, newest first, with Mastodon's cursors.
 */
final class IdPages {

    private IdPages() {
    }

    /**
     * @param select     a query ending in its {@code WHERE} conditions, which the cursors' are added to
     * @param parameters what {@code select} binds
     * @param column     the ID column to page by
     * @param minId      takes priority over {@code sinceId}, and starts from the row closest to it
     */
    static <T> List<T> find(JdbcClient jdbc, String select, Map<String, ?> parameters, String column,
                            UUID maxId, UUID sinceId, UUID minId, int limit,
                            Function<JdbcClient.StatementSpec, List<T>> read) {
        var sql = new StringBuilder(select);
        var bound = new HashMap<String, Object>(parameters);
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        if (maxId != null) {
            sql.append(" AND ").append(column).append(" < :maxId");
            bound.put("maxId", maxId);
        }
        var ascending = minId != null;
        if (ascending) {
            sql.append(" AND ").append(column).append(" > :minId");
            bound.put("minId", minId);
        } else if (sinceId != null) {
            sql.append(" AND ").append(column).append(" > :sinceId");
            bound.put("sinceId", sinceId);
        }
        sql.append(" ORDER BY ").append(column).append(ascending ? " ASC" : " DESC").append(" LIMIT :limit");
        bound.put("limit", limit);
        var found = read.apply(jdbc.sql(sql.toString()).params(bound));
        return ascending ? found.reversed() : found;
    }
}
