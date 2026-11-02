package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.AccountStats;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class StatusRepository {

    private static final String COLUMNS = """
            s.id, s.uri, s.actor_id, s.text, s.content, s.spoiler_text, s.language, s.visibility, s.sensitive,
            s.in_reply_to_id, s.in_reply_to_account_id, s.published_at""";

    private static final String SELECT = "SELECT " + COLUMNS + """
            ,
                   (SELECT count(*) FROM status r
                    WHERE r.in_reply_to_id = s.id AND r.visibility IN ('public', 'unlisted')) AS replies_count
            FROM status s
            """;

    /**
     * Excludes replies to other accounts, but keeps an account's replies to itself.
     */
    private static final String NOT_A_REPLY_TO_OTHERS =
            "(s.in_reply_to_account_id IS NULL OR s.in_reply_to_account_id = s.actor_id)";

    private static final RowMapper<Status> STATUS_MAPPER = (rs, rowNum) -> new Status(
            rs.getObject("id", UUID.class),
            URI.create(rs.getString("uri")),
            rs.getObject("actor_id", UUID.class),
            rs.getString("text"),
            rs.getString("content"),
            rs.getString("spoiler_text"),
            rs.getString("language"),
            Visibility.fromValue(rs.getString("visibility")),
            rs.getBoolean("sensitive"),
            rs.getObject("in_reply_to_id", UUID.class),
            rs.getObject("in_reply_to_account_id", UUID.class),
            rs.getObject("published_at", OffsetDateTime.class).toInstant(),
            rs.getLong("replies_count"));

    private final JdbcClient jdbc;

    StatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Status status) {
        jdbc.sql("""
                        INSERT INTO status (id, uri, actor_id, text, content, spoiler_text, language, visibility, sensitive,
                                            in_reply_to_id, in_reply_to_account_id, published_at)
                        VALUES (:id, :uri, :actorId, :text, :content, :spoilerText, :language, :visibility, :sensitive,
                                :inReplyToId, :inReplyToAccountId, :publishedAt)
                        """)
                .param("id", status.id())
                .param("uri", status.uri().toString())
                .param("actorId", status.actorId())
                .param("text", status.text())
                .param("content", status.content())
                .param("spoilerText", status.spoilerText())
                .param("language", status.language(), Types.VARCHAR)
                .param("visibility", status.visibility().value())
                .param("sensitive", status.sensitive())
                .param("inReplyToId", status.inReplyToId(), Types.OTHER)
                .param("inReplyToAccountId", status.inReplyToAccountId(), Types.OTHER)
                .param("publishedAt", status.publishedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<Status> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE s.id = :id").param("id", id).query(STATUS_MAPPER).optional();
    }

    /**
     * Finds a status and stops it from being deleted until the transaction ends, so that a reply to it can be inserted
     * without a concurrent delete breaking the foreign key.
     */
    public Optional<Status> findByIdAndLock(UUID id) {
        return jdbc.sql(SELECT + "WHERE s.id = :id FOR KEY SHARE OF s").param("id", id).query(STATUS_MAPPER).optional();
    }

    /**
     * @return the deleted status, or empty if this actor has no status with this ID
     */
    public Optional<Status> delete(UUID id, UUID actorId) {
        return jdbc.sql("DELETE FROM status s WHERE s.id = :id AND s.actor_id = :actorId RETURNING " + COLUMNS
                        + ", 0 AS replies_count")
                .param("id", id)
                .param("actorId", actorId)
                .query(STATUS_MAPPER)
                .optional();
    }

    public List<Status> findPublic(StatusPage page) {
        return findPage("s.visibility = 'public' AND " + NOT_A_REPLY_TO_OTHERS, Map.of(), page);
    }

    /**
     * @param includeAll whether to include private and direct statuses, for the account's own view
     */
    public List<Status> findByAccount(UUID actorId, boolean includeAll, boolean excludeReplies, StatusPage page) {
        var filter = new StringBuilder("s.actor_id = :actorId");
        if (!includeAll) {
            filter.append(" AND s.visibility IN ('public', 'unlisted')");
        }
        if (excludeReplies) {
            filter.append(" AND ").append(NOT_A_REPLY_TO_OTHERS);
        }
        return findPage(filter.toString(), Map.of("actorId", actorId), page);
    }

    /**
     * @return the stats of each account that has any counted statuses
     */
    public Map<UUID, AccountStats> statsByAccount(Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        var stats = new HashMap<UUID, AccountStats>();
        jdbc.sql("""
                        SELECT actor_id, count(*) AS statuses_count, max(published_at) AS last_status_at
                        FROM status
                        WHERE actor_id IN (:actorIds) AND visibility <> 'direct'
                        GROUP BY actor_id
                        """)
                .param("actorIds", actorIds)
                .query(rs -> {
                    stats.put(rs.getObject("actor_id", UUID.class), new AccountStats(
                            rs.getLong("statuses_count"),
                            rs.getObject("last_status_at", OffsetDateTime.class).toInstant()));
                });
        return stats;
    }

    public long countExcludingDirect() {
        return jdbc.sql("SELECT count(*) FROM status WHERE visibility <> 'direct'").query(Long.class).single();
    }

    private List<Status> findPage(String filter, Map<String, ?> parameters, StatusPage page) {
        var sql = new StringBuilder(SELECT).append("WHERE ").append(filter);
        var cursors = new HashMap<String, Object>(parameters);
        if (page.maxId() != null) {
            sql.append(" AND s.id < :maxId");
            cursors.put("maxId", page.maxId());
        }
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        var ascending = page.minId() != null;
        if (ascending) {
            sql.append(" AND s.id > :minId");
            cursors.put("minId", page.minId());
        } else if (page.sinceId() != null) {
            sql.append(" AND s.id > :sinceId");
            cursors.put("sinceId", page.sinceId());
        }
        sql.append(ascending ? " ORDER BY s.id ASC" : " ORDER BY s.id DESC").append(" LIMIT :limit");
        cursors.put("limit", page.limit());

        var statuses = jdbc.sql(sql.toString()).params(cursors).query(STATUS_MAPPER).list();
        return ascending ? statuses.reversed() : statuses;
    }
}
