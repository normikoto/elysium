package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.ContentType;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.QuotePolicy;
import dev.biribiri.elysium.content.ScheduledStatus;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ScheduledStatusRepository {

    private static final String COLUMNS = "id, actor_id, scheduled_at, params";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ScheduledStatusRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * How a draft is stored. Its own record, with enums by their API values, so that renaming something in
     * {@link NewStatus} can't make what's already stored unreadable.
     */
    private record Params(
            String text, String spoilerText, String language, String visibility, boolean sensitive, UUID inReplyToId,
            UUID quotedStatusId, String quotePolicy, Poll poll, boolean localOnly, String contentType) {

        record Poll(List<String> options, long expiresIn, boolean multiple, boolean hideTotals) {
        }
    }

    /**
     * @param draft what to post, whose media isn't stored here, but held with
     *              {@link MediaAttachmentRepository#hold}
     */
    public void insert(UUID id, UUID actorId, Instant scheduledAt, NewStatus draft) {
        var poll = draft.poll() == null ? null : new Params.Poll(draft.poll().options(), draft.poll().expiresIn(),
                draft.poll().multiple(), draft.poll().hideTotals());
        var params = new Params(draft.text(), draft.spoilerText(), draft.language(), draft.visibility().value(),
                draft.sensitive(), draft.inReplyToId(), draft.quotedStatusId(),
                draft.quotePolicy() == null ? null : draft.quotePolicy().value(), poll, draft.localOnly(),
                draft.contentType().value());
        jdbc.sql("""
                        INSERT INTO scheduled_status (id, actor_id, scheduled_at, params)
                        VALUES (:id, :actorId, :scheduledAt, CAST(:params AS jsonb))
                        """)
                .param("id", id)
                .param("actorId", actorId)
                .param("scheduledAt", Timestamp.from(scheduledAt))
                .param("params", json.writeValueAsString(params))
                .update();
    }

    /**
     * @return one of an account's, with no media, which is held in {@code media_attachment}
     */
    public Optional<ScheduledStatus> find(UUID id, UUID actorId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scheduled_status WHERE id = :id AND actor_id = :actorId")
                .param("id", id)
                .param("actorId", actorId)
                .query(this::map)
                .optional();
    }

    /**
     * @return one with no media, which is held in {@code media_attachment}
     */
    public Optional<ScheduledStatus> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scheduled_status WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /**
     * Locks one until the transaction ends, so that it's only posted once, and can't be changed while it's posted.
     */
    public Optional<ScheduledStatus> findAndLock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scheduled_status WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /**
     * @return an account's, newest first by when they were scheduled, not when they're due, as on Mastodon
     */
    public List<ScheduledStatus> findPage(UUID actorId, StatusPage page) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM scheduled_status WHERE actor_id = :actorId");
        var parameters = new HashMap<String, Object>();
        parameters.put("actorId", actorId);
        if (page.maxId() != null) {
            sql.append(" AND id < :maxId");
            parameters.put("maxId", page.maxId());
        }
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        var ascending = page.minId() != null;
        var after = ascending ? page.minId() : page.sinceId();
        if (after != null) {
            sql.append(" AND id > :after");
            parameters.put("after", after);
        }
        sql.append(ascending ? " ORDER BY id ASC" : " ORDER BY id DESC").append(" LIMIT :limit");
        parameters.put("limit", page.limit());
        var found = jdbc.sql(sql.toString()).params(parameters).query(this::map).list();
        return ascending ? found.reversed() : found;
    }

    /**
     * @return the IDs of those that are due, soonest first
     */
    public List<UUID> findDue(int limit) {
        return jdbc.sql("""
                        SELECT id FROM scheduled_status WHERE scheduled_at <= now()
                        ORDER BY scheduled_at, id LIMIT :limit
                        """)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public int count(UUID actorId) {
        return jdbc.sql("SELECT count(*) FROM scheduled_status WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(Integer.class)
                .single();
    }

    /**
     * @param except one not to count, which is being moved to that day, or null
     * @return how many of an account's are due on a day, in UTC
     */
    public int countOn(UUID actorId, LocalDate day, UUID except) {
        var start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_status
                        WHERE actor_id = :actorId AND scheduled_at >= :start AND scheduled_at < :end
                            AND id IS DISTINCT FROM CAST(:except AS uuid)
                        """)
                .param("actorId", actorId)
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(start.plusSeconds(24 * 60 * 60)))
                .param("except", except == null ? null : except.toString())
                .query(Integer.class)
                .single();
    }

    /**
     * Serializes changes to an account's, so that two requests can't both get past its limits.
     */
    public void lockActor(UUID actorId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "scheduled_status\n" + actorId).query((rs, row) -> true).single();
    }

    public boolean reschedule(UUID id, UUID actorId, Instant scheduledAt) {
        return jdbc.sql("UPDATE scheduled_status SET scheduled_at = :scheduledAt WHERE id = :id AND actor_id = :actorId")
                .param("id", id)
                .param("actorId", actorId)
                .param("scheduledAt", Timestamp.from(scheduledAt))
                .update() == 1;
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM scheduled_status WHERE id = :id").param("id", id).update() == 1;
    }

    /**
     * @return the IDs of an account's
     */
    public List<UUID> findIdsBy(UUID actorId) {
        return jdbc.sql("SELECT id FROM scheduled_status WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(UUID.class)
                .list();
    }

    public void removeAllBy(UUID actorId) {
        jdbc.sql("DELETE FROM scheduled_status WHERE actor_id = :actorId").param("actorId", actorId).update();
    }

    private ScheduledStatus map(ResultSet rs, int row) throws SQLException {
        var params = json.readValue(rs.getString("params"), Params.class);
        var poll = params.poll() == null ? null : new NewPoll(params.poll().options(), params.poll().expiresIn(),
                params.poll().multiple(), params.poll().hideTotals());
        var draft = new NewStatus(params.text(), params.spoilerText(), params.language(),
                Visibility.fromValue(params.visibility()), params.sensitive(), params.inReplyToId(), List.of(),
                params.quotedStatusId(), params.quotePolicy() == null ? null
                : QuotePolicy.fromValue(params.quotePolicy()).orElseThrow(), poll, params.localOnly(),
                ContentType.fromValue(params.contentType()).orElseThrow());
        return new ScheduledStatus(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class),
                rs.getTimestamp("scheduled_at").toInstant(), draft, List.of());
    }
}
