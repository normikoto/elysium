package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.IdPage;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     */
    static <T> List<T> find(JdbcClient jdbc, String select, Map<String, ?> parameters, String column, IdPage page,
                            Function<JdbcClient.StatementSpec, List<T>> read) {
        var bound = new HashMap<String, Object>(parameters);
        var sql = select + page.cursors(column, bound) + page.orderAndLimit(column, bound);
        return page.newestFirst(read.apply(jdbc.sql(sql).params(bound)));
    }
}
