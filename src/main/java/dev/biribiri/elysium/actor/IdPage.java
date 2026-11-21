package dev.biribiri.elysium.actor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Selects one page of a list that's paged by IDs, with Mastodon's cursors, such as statuses, accounts that follow
 * someone, or reports. What the cursors are the IDs of is up to the list: a list of accounts can be paged by its follows'
 * IDs. Results are always newest first.
 *
 * <p>How many a page can hold is up to whoever asks for it, since that's part of each API. This only makes sure there's
 * room for something.
 *
 * @param maxId   only those older than this, or null
 * @param sinceId only those newer than this, starting from the newest, or null
 * @param minId   only those newer than this, starting from the closest to it, or null. Takes priority over
 *                {@code sinceId}, and the page is still newest first.
 */
public record IdPage(int limit, UUID maxId, UUID sinceId, UUID minId) {

    public IdPage {
        if (limit < 1) {
            throw new IllegalArgumentException("A page has to have room for something, but its limit is " + limit);
        }
    }

    /**
     * Whether the page starts from the oldest of what it can list, because of {@code minId}, so that a query has to choose
     * oldest first and then reverse them, as {@link #newestFirst} does.
     */
    public boolean fromOldest() {
        return minId != null;
    }

    /**
     * What the page has to be newer than, or null.
     */
    public UUID after() {
        return minId != null ? minId : sinceId;
    }

    /**
     * The conditions on a time-ordered ID column that the cursors make, to add to a query's {@code WHERE}. They're only there
     * for the cursors that are given, because PostgreSQL can't determine the type of a null parameter.
     *
     * @param parameters where this binds {@code maxId} and {@code after}
     */
    public String cursors(String column, Map<String, Object> parameters) {
        var sql = new StringBuilder();
        if (maxId != null) {
            sql.append(" AND ").append(column).append(" < :maxId");
            parameters.put("maxId", maxId);
        }
        if (after() != null) {
            sql.append(" AND ").append(column).append(" > :after");
            parameters.put("after", after());
        }
        return sql.toString();
    }

    /**
     * Orders by a time-ordered ID column and limits the query to the page, after {@link #cursors}. What it finds goes
     * through {@link #newestFirst}.
     *
     * @param parameters where this binds {@code limit}
     */
    public String orderAndLimit(String column, Map<String, Object> parameters) {
        parameters.put("limit", limit);
        return " ORDER BY " + column + (fromOldest() ? " ASC" : " DESC") + " LIMIT :limit";
    }

    /**
     * Puts what a query found for the page newest first, which it isn't when the page {@link #fromOldest starts from the
     * oldest}.
     */
    public <T> List<T> newestFirst(List<T> found) {
        return fromOldest() ? found.reversed() : found;
    }
}
