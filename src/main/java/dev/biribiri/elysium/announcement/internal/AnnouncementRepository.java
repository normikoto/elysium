package dev.biribiri.elysium.announcement.internal;

import dev.biribiri.elysium.actor.ActorSql;
import dev.biribiri.elysium.announcement.Announcement;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class AnnouncementRepository {

    private static final String COLUMNS =
            "id, text, starts_at, ends_at, all_day, published_at, created_at, updated_at";

    /**
     * Mastodon's order, so that an announcement of something that happens later goes after one of something sooner.
     */
    private static final String ORDER = "ORDER BY COALESCE(starts_at, published_at, created_at), id";

    private static final RowMapper<Announcement> MAPPER = (rs, row) -> new Announcement(
            rs.getObject("id", UUID.class),
            rs.getString("text"),
            instant(rs, "starts_at"),
            instant(rs, "ends_at"),
            rs.getBoolean("all_day"),
            instant(rs, "published_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    AnnouncementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One reaction summary row.
     *
     * @param emojiId the custom emoji, or null for a Unicode one
     */
    public record Summary(UUID announcementId, String name, UUID emojiId, long count, boolean me) {
    }

    public List<Announcement> findAll() {
        return jdbc.sql("SELECT %s FROM announcement %s".formatted(COLUMNS, ORDER)).query(MAPPER).list();
    }

    public List<Announcement> findPublished() {
        return jdbc.sql("SELECT %s FROM announcement WHERE published_at IS NOT NULL %s".formatted(COLUMNS, ORDER))
                .query(MAPPER)
                .list();
    }

    public Optional<Announcement> findPublished(UUID id) {
        return jdbc.sql("SELECT %s FROM announcement WHERE id = :id AND published_at IS NOT NULL".formatted(COLUMNS))
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Holds it until the transaction ends, so that reactions added at once are counted one after the other, and it
     * can't be unpublished or deleted meanwhile.
     */
    public Optional<Announcement> lockPublished(UUID id) {
        return jdbc.sql("""
                        SELECT %s FROM announcement WHERE id = :id AND published_at IS NOT NULL FOR UPDATE
                        """.formatted(COLUMNS))
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public Announcement insert(UUID id, String text, Instant startsAt, Instant endsAt, boolean allDay,
                               Instant publishedAt, Instant now) {
        return jdbc.sql("""
                        INSERT INTO announcement (id, text, starts_at, ends_at, all_day, published_at, created_at,
                                                  updated_at)
                        VALUES (:id, :text, :startsAt, :endsAt, :allDay, :publishedAt, :now, :now)
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("text", text)
                .param("startsAt", offset(startsAt))
                .param("endsAt", offset(endsAt))
                .param("allDay", allDay)
                .param("publishedAt", offset(publishedAt))
                .param("now", offset(now))
                .query(MAPPER)
                .single();
    }

    public Optional<Announcement> update(UUID id, String text, Instant startsAt, Instant endsAt, boolean allDay,
                                         Instant now) {
        return jdbc.sql("""
                        UPDATE announcement
                        SET text = :text, starts_at = :startsAt, ends_at = :endsAt, all_day = :allDay, updated_at = :now
                        WHERE id = :id
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("text", text)
                .param("startsAt", offset(startsAt))
                .param("endsAt", offset(endsAt))
                .param("allDay", allDay)
                .param("now", offset(now))
                .query(MAPPER)
                .optional();
    }

    /**
     * Publishing one that's published already keeps the time it was.
     */
    public boolean publish(UUID id, Instant now) {
        return jdbc.sql("UPDATE announcement SET published_at = COALESCE(published_at, :now) WHERE id = :id")
                .param("id", id)
                .param("now", offset(now))
                .update() == 1;
    }

    public boolean unpublish(UUID id) {
        return jdbc.sql("UPDATE announcement SET published_at = NULL WHERE id = :id").param("id", id).update() == 1;
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM announcement WHERE id = :id").param("id", id).update() == 1;
    }

    public void dismiss(UUID announcementId, UUID actorId) {
        jdbc.sql("""
                        INSERT INTO announcement_dismissal (announcement_id, actor_id) VALUES (:announcementId, :actorId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("announcementId", announcementId)
                .param("actorId", actorId)
                .update();
    }

    public Set<UUID> findDismissed(UUID actorId, Collection<UUID> announcementIds) {
        if (announcementIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT announcement_id FROM announcement_dismissal
                        WHERE actor_id = :actorId AND announcement_id IN (:ids)
                        """)
                .param("actorId", actorId)
                .param("ids", announcementIds)
                .query(UUID.class)
                .list());
    }

    public boolean hasReaction(UUID announcementId, String name, UUID emojiId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM announcement_reaction
                                       WHERE announcement_id = :announcementId AND name = :name
                                         AND emoji_id IS NOT DISTINCT FROM :emojiId)
                        """)
                .param("announcementId", announcementId)
                .param("name", name)
                .param("emojiId", emojiId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    /**
     * Counts reactions from accounts that are gone too, so that the limit can't be passed while one is suspended and
     * then exceeded once it's lifted.
     */
    public int countEmoji(UUID announcementId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM (SELECT DISTINCT name, emoji_id FROM announcement_reaction
                                              WHERE announcement_id = :announcementId) AS used
                        """)
                .param("announcementId", announcementId)
                .query(Integer.class)
                .single();
    }

    public void insertReaction(UUID announcementId, UUID actorId, String name, UUID emojiId, Instant now) {
        jdbc.sql("""
                        INSERT INTO announcement_reaction (announcement_id, actor_id, name, emoji_id, created_at)
                        VALUES (:announcementId, :actorId, :name, :emojiId, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("announcementId", announcementId)
                .param("actorId", actorId)
                .param("name", name)
                .param("emojiId", emojiId, Types.OTHER)
                .param("now", offset(now))
                .update();
    }

    public boolean hasReacted(UUID announcementId, UUID actorId, String name, UUID emojiId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM announcement_reaction
                                       WHERE announcement_id = :announcementId AND actor_id = :actorId AND name = :name
                                         AND emoji_id IS NOT DISTINCT FROM :emojiId)
                        """)
                .param("announcementId", announcementId)
                .param("actorId", actorId)
                .param("name", name)
                .param("emojiId", emojiId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    public void deleteReaction(UUID announcementId, UUID actorId, String name, UUID emojiId) {
        jdbc.sql("""
                        DELETE FROM announcement_reaction
                        WHERE announcement_id = :announcementId AND actor_id = :actorId AND name = :name
                          AND emoji_id IS NOT DISTINCT FROM :emojiId
                        """)
                .param("announcementId", announcementId)
                .param("actorId", actorId)
                .param("name", name)
                .param("emojiId", emojiId, Types.OTHER)
                .update();
    }

    /**
     * Each announcement's reactions by emoji, in the order each emoji was first used, from accounts that are active.
     */
    public List<Summary> summaries(Collection<UUID> announcementIds, UUID viewer) {
        if (announcementIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT r.announcement_id, r.name, r.emoji_id, COUNT(*) AS count,
                               COALESCE(BOOL_OR(r.actor_id = :viewer), FALSE) AS me
                        FROM announcement_reaction r
                        JOIN actor a ON a.id = r.actor_id
                        WHERE r.announcement_id IN (:ids) AND %s
                        GROUP BY r.announcement_id, r.name, r.emoji_id
                        ORDER BY r.announcement_id, MIN(r.created_at), r.name
                        """.formatted(ActorSql.active("a")))
                .param("ids", announcementIds)
                .param("viewer", viewer, Types.OTHER)
                .query((rs, row) -> new Summary(
                        rs.getObject("announcement_id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("emoji_id", UUID.class),
                        rs.getLong("count"),
                        rs.getBoolean("me")))
                .list();
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM announcement_dismissal WHERE actor_id = :actorId").param("actorId", actorId).update();
        jdbc.sql("DELETE FROM announcement_reaction WHERE actor_id = :actorId").param("actorId", actorId).update();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
