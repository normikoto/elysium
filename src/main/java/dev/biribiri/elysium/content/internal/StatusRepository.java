package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.AccountStats;
import dev.biribiri.elysium.content.DeletedStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Types;
import java.time.Instant;
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
            s.id, s.uri, s.actor_id, s.text, s.content, s.spoiler_text, s.language, s.visibility, s.sensitive, s.reply,
            s.in_reply_to_id, s.in_reply_to_account_id, s.published_at""";

    private static final String COUNT_REPLIES = """
            SELECT count(*) FROM status r
            JOIN actor replier ON replier.id = r.actor_id AND replier.suspended_at IS NULL AND replier.deleted_at IS NULL
            WHERE r.in_reply_to_id = %s AND r.visibility IN ('public', 'unlisted')""";

    /**
     * Only finds statuses of accounts that are neither suspended nor deleted, so that those of a suspended account aren't
     * served by anything, and aren't left to be filtered out after a page of them has been chosen.
     */
    private static final String SELECT = "SELECT " + COLUMNS + ", (" + COUNT_REPLIES.formatted("s.id")
            + ") AS replies_count FROM status s "
            + "JOIN actor author ON author.id = s.actor_id AND author.suspended_at IS NULL AND author.deleted_at IS NULL ";

    private static final int REMOVAL_BATCH_SIZE = 1000;

    /**
     * Excludes replies to other accounts, but keeps an account's replies to itself.
     */
    private static final String NOT_A_REPLY_TO_OTHERS = "(NOT s.reply OR s.in_reply_to_account_id = s.actor_id)";

    /**
     * Identifies the lock on assigning status IDs. Any value works, as long as nothing else uses it as an advisory lock.
     */
    private static final long ID_ASSIGNMENT_LOCK = 0x656C79_7374617475L;

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
            rs.getBoolean("reply"),
            rs.getObject("in_reply_to_id", UUID.class),
            rs.getObject("in_reply_to_account_id", UUID.class),
            rs.getObject("published_at", OffsetDateTime.class).toInstant(),
            rs.getLong("replies_count"));

    private final JdbcClient jdbc;

    StatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes other transactions wait here until this one ends. Each statement runs on a new snapshot, so whatever runs
     * after this sees the statuses that those before it committed.
     */
    public void lockIdAssignment() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", ID_ASSIGNMENT_LOCK)
                .query((rs, rowNum) -> true)
                .single();
    }

    public Optional<UUID> findNewestId() {
        return jdbc.sql("SELECT id FROM status ORDER BY id DESC LIMIT 1").query(UUID.class).optional();
    }

    public void insert(Status status) {
        jdbc.sql("""
                        INSERT INTO status (id, uri, actor_id, text, content, spoiler_text, language, visibility, sensitive,
                                            reply, in_reply_to_id, in_reply_to_account_id, published_at)
                        VALUES (:id, :uri, :actorId, :text, :content, :spoilerText, :language, :visibility, :sensitive,
                                :reply, :inReplyToId, :inReplyToAccountId, :publishedAt)
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
                .param("reply", status.reply())
                .param("inReplyToId", status.inReplyToId(), Types.OTHER)
                .param("inReplyToAccountId", status.inReplyToAccountId(), Types.OTHER)
                .param("publishedAt", status.publishedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<Status> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE s.id = :id").param("id", id).query(STATUS_MAPPER).optional();
    }

    /**
     * Finds a status and stops it from being deleted until the transaction ends, such as so that a reply to it can be
     * inserted without a concurrent delete breaking the foreign key.
     */
    public Optional<Status> findByIdAndLock(UUID id) {
        return jdbc.sql(SELECT + "WHERE s.id = :id FOR KEY SHARE OF s").param("id", id).query(STATUS_MAPPER).optional();
    }

    /**
     * @return the deleted status, or empty if this actor has no status with this ID
     */
    public Optional<Status> delete(UUID id, UUID actorId) {
        return jdbc.sql("WITH replies AS (SELECT (" + COUNT_REPLIES.formatted(":id") + ") AS replies_count) "
                        + "DELETE FROM status s USING replies WHERE s.id = :id AND s.actor_id = :actorId "
                        + "RETURNING " + COLUMNS + ", replies.replies_count")
                .param("id", id)
                .param("actorId", actorId)
                .query(STATUS_MAPPER)
                .optional();
    }

    /**
     * Removes all of an actor's statuses and tombstones. Each batch commits by itself, so that removing a lot of them doesn't
     * hold one long transaction.
     */
    public void removeAllBy(UUID actorId) {
        int removed;
        do {
            removed = jdbc.sql("""
                            DELETE FROM status
                            WHERE id IN (SELECT id FROM status WHERE actor_id = :actorId LIMIT :limit)
                            """)
                    .param("actorId", actorId)
                    .param("limit", REMOVAL_BATCH_SIZE)
                    .update();
        } while (removed == REMOVAL_BATCH_SIZE);
        jdbc.sql("DELETE FROM status_tombstone WHERE actor_id = :actorId").param("actorId", actorId).update();
    }

    public void insertTombstone(Status status, Instant deletedAt) {
        jdbc.sql("""
                        INSERT INTO status_tombstone (status_id, uri, actor_id, deleted_at)
                        VALUES (:id, :uri, :actorId, :deletedAt)
                        """)
                .param("id", status.id())
                .param("uri", status.uri().toString())
                .param("actorId", status.actorId())
                .param("deletedAt", deletedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<DeletedStatus> findTombstone(UUID id, UUID actorId) {
        return jdbc.sql("SELECT uri, deleted_at FROM status_tombstone WHERE status_id = :id AND actor_id = :actorId")
                .param("id", id)
                .param("actorId", actorId)
                .query((rs, rowNum) -> new DeletedStatus(
                        URI.create(rs.getString("uri")),
                        rs.getObject("deleted_at", OffsetDateTime.class).toInstant()))
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
                        SELECT c.actor_id, c.statuses_count, (
                            SELECT max(s.published_at)
                            FROM status s
                            WHERE s.actor_id = c.actor_id AND s.visibility <> 'direct'
                        ) AS last_status_at
                        FROM actor_status_count c
                        WHERE c.actor_id IN (:actorIds) AND c.statuses_count > 0
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
        return jdbc.sql("""
                        SELECT coalesce(sum(c.statuses_count), 0)::bigint FROM actor_status_count c
                        JOIN actor ON actor.id = c.actor_id
                        WHERE actor.suspended_at IS NULL AND actor.deleted_at IS NULL
                        """)
                .query(Long.class)
                .single();
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
