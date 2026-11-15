package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.PostFilter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class PostFilterRepository {

    private final JdbcClient jdbc;

    PostFilterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes another change to the account's filters wait until this transaction ends, so that limits are counted
     * correctly.
     */
    public void lockAccount(UUID accountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "post_filter\n" + accountId).query((rs, row) -> true).single();
    }

    /**
     * @return the account's filters, oldest first
     */
    public List<PostFilter> findByAccount(UUID accountId) {
        return load(jdbc.sql(SELECT + "WHERE account_id = :account ORDER BY created_at, id")
                .param("account", accountId).query(FilterRow.MAPPER).list());
    }

    /**
     * @return the account's filters that haven't expired by then, oldest first
     */
    public List<PostFilter> findActiveByAccount(UUID accountId, Instant now) {
        return load(jdbc.sql(SELECT + """
                        WHERE account_id = :account AND (expires_at IS NULL OR expires_at > :now) ORDER BY created_at, id""")
                .param("account", accountId).param("now", ContentRows.offsetOrNull(now)).query(FilterRow.MAPPER).list());
    }

    public Optional<PostFilter> find(UUID accountId, UUID id) {
        return load(jdbc.sql(SELECT + "WHERE account_id = :account AND id = :id")
                .param("account", accountId).param("id", id).query(FilterRow.MAPPER).list()).stream().findFirst();
    }

    /**
     * @return the filter that holds the keyword, if the account owns it
     */
    public Optional<UUID> findFilterOfKeyword(UUID accountId, UUID keywordId) {
        return jdbc.sql("""
                        SELECT f.id FROM post_filter_keyword k JOIN post_filter f ON f.id = k.filter_id
                        WHERE k.id = :id AND f.account_id = :account""")
                .param("id", keywordId).param("account", accountId).query(UUID.class).optional();
    }

    /**
     * @return the filter that holds the status, if the account owns it
     */
    public Optional<UUID> findFilterOfStatus(UUID accountId, UUID filteredStatusId) {
        return jdbc.sql("""
                        SELECT f.id FROM post_filter_status s JOIN post_filter f ON f.id = s.filter_id
                        WHERE s.id = :id AND f.account_id = :account""")
                .param("id", filteredStatusId).param("account", accountId).query(UUID.class).optional();
    }

    public int countByAccount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM post_filter WHERE account_id = :account")
                .param("account", accountId).query(Integer.class).single();
    }

    public UUID insert(UUID accountId, String title, Set<PostFilter.Context> contexts, PostFilter.Action action,
                       Instant expiresAt) {
        var now = Instant.now();
        var id = Ids.timeOrdered(now);
        jdbc.sql("""
                        INSERT INTO post_filter (id, account_id, title, context, action, expires_at, created_at)
                        VALUES (:id, :account, :title, CAST(:context AS text[]), :action, :expiresAt, :createdAt)""")
                .param("id", id).param("account", accountId).param("title", title)
                .param("context", contextArray(contexts), Types.VARCHAR).param("action", action.value())
                .param("expiresAt", ContentRows.offsetOrNull(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", ContentRows.offsetOrNull(now))
                .update();
        return id;
    }

    public void update(UUID id, String title, Set<PostFilter.Context> contexts, PostFilter.Action action,
                       Instant expiresAt) {
        jdbc.sql("""
                        UPDATE post_filter SET title = :title, context = CAST(:context AS text[]), action = :action,
                            expires_at = :expiresAt
                        WHERE id = :id""")
                .param("id", id).param("title", title).param("context", contextArray(contexts), Types.VARCHAR)
                .param("action", action.value())
                .param("expiresAt", ContentRows.offsetOrNull(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM post_filter WHERE id = :id").param("id", id).update();
    }

    public PostFilter.Keyword insertKeyword(UUID filterId, String keyword, boolean wholeWord) {
        var now = Instant.now();
        var created = new PostFilter.Keyword(Ids.timeOrdered(now), keyword, wholeWord);
        jdbc.sql("""
                        INSERT INTO post_filter_keyword (id, filter_id, keyword, whole_word, created_at)
                        VALUES (:id, :filter, :keyword, :wholeWord, :createdAt)""")
                .param("id", created.id()).param("filter", filterId).param("keyword", keyword)
                .param("wholeWord", wholeWord).param("createdAt", ContentRows.offsetOrNull(now)).update();
        return created;
    }

    public void updateKeyword(UUID id, String keyword, boolean wholeWord) {
        jdbc.sql("UPDATE post_filter_keyword SET keyword = :keyword, whole_word = :wholeWord WHERE id = :id")
                .param("id", id).param("keyword", keyword).param("wholeWord", wholeWord).update();
    }

    public void deleteKeyword(UUID id) {
        jdbc.sql("DELETE FROM post_filter_keyword WHERE id = :id").param("id", id).update();
    }

    /**
     * @return the new entry, or empty if the filter already has the status
     */
    public Optional<PostFilter.FilteredStatus> insertStatus(UUID filterId, UUID statusId) {
        var now = Instant.now();
        var created = new PostFilter.FilteredStatus(Ids.timeOrdered(now), statusId);
        var inserted = jdbc.sql("""
                        INSERT INTO post_filter_status (id, filter_id, status_id, created_at)
                        VALUES (:id, :filter, :status, :createdAt)
                        ON CONFLICT (filter_id, status_id) DO NOTHING""")
                .param("id", created.id()).param("filter", filterId).param("status", statusId)
                .param("createdAt", ContentRows.offsetOrNull(now)).update();
        return inserted > 0 ? Optional.of(created) : Optional.empty();
    }

    public void deleteStatus(UUID id) {
        jdbc.sql("DELETE FROM post_filter_status WHERE id = :id").param("id", id).update();
    }

    public void deleteForAccount(UUID accountId) {
        jdbc.sql("DELETE FROM post_filter WHERE account_id = :account").param("account", accountId).update();
    }

    private static final String SELECT = "SELECT id, title, context, action, expires_at FROM post_filter ";

    private List<PostFilter> load(List<FilterRow> rows) {
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(FilterRow::id).toList();
        var keywords = keywords(ids);
        var statuses = statuses(ids);
        return rows.stream().map(row -> new PostFilter(row.id(), row.title(), row.contexts(), row.action(),
                row.expiresAt(), keywords.getOrDefault(row.id(), List.of()), statuses.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private Map<UUID, List<PostFilter.Keyword>> keywords(Collection<UUID> filterIds) {
        return jdbc.sql("""
                        SELECT id, filter_id, keyword, whole_word FROM post_filter_keyword WHERE filter_id IN (:ids)
                        ORDER BY created_at, id""")
                .param("ids", filterIds)
                .query((rs, row) -> Map.entry(rs.getObject("filter_id", UUID.class), new PostFilter.Keyword(
                        rs.getObject("id", UUID.class), rs.getString("keyword"), rs.getBoolean("whole_word"))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private Map<UUID, List<PostFilter.FilteredStatus>> statuses(Collection<UUID> filterIds) {
        return jdbc.sql("""
                        SELECT id, filter_id, status_id FROM post_filter_status WHERE filter_id IN (:ids)
                        ORDER BY created_at, id""")
                .param("ids", filterIds)
                .query((rs, row) -> Map.entry(rs.getObject("filter_id", UUID.class), new PostFilter.FilteredStatus(
                        rs.getObject("id", UUID.class), rs.getObject("status_id", UUID.class))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    /**
     * @return the contexts as a PostgreSQL array literal. Their names never need quoting.
     */
    private static String contextArray(Set<PostFilter.Context> contexts) {
        return contexts.stream().map(PostFilter.Context::value).sorted().collect(Collectors.joining(",", "{", "}"));
    }

    private record FilterRow(UUID id, String title, Set<PostFilter.Context> contexts, PostFilter.Action action,
                             Instant expiresAt) {

        static final RowMapper<FilterRow> MAPPER = (rs, row) -> {
            var expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            return new FilterRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    Arrays.stream((String[]) rs.getArray("context").getArray())
                            .map(PostFilter.Context::fromValue).collect(Collectors.toSet()),
                    PostFilter.Action.fromValue(rs.getString("action")),
                    expiresAt == null ? null : expiresAt.toInstant());
        };
    }
}
