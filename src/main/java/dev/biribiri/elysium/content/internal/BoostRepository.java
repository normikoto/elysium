package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.BoostStats;
import dev.biribiri.elysium.content.StatusBoost;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Boosts, which are rows in {@code status} with a {@code reblog_of_id}.
 */
@Repository
public class BoostRepository {

    private static final String COLUMNS = "b.id, b.uri, b.actor_id, b.reblog_of_id AS status_id, b.visibility, b.published_at, b.activity_at";

    private static final RowMapper<StatusBoost> MAPPER = new SimplePropertyRowMapper<>(StatusBoost.class, ContentRows.CONVERSIONS);

    private final JdbcClient jdbc;

    BoostRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes everything that changes whether this actor boosts this status, which one row at most can say.
     */
    public void lockPair(URI actorUri, UUID statusId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "boost\n" + actorUri + "\n" + statusId)
                .query((rs, row) -> true)
                .single();
    }

    public Optional<UUID> findStatusIdByUri(URI uri) {
        return jdbc.sql("SELECT id FROM status WHERE uri = :uri AND reblog_of_id IS NULL")
                .param("uri", uri.toString()).query(UUID.class).optional();
    }

    public Optional<StatusBoost> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status b WHERE b.id = :id AND b.reblog_of_id IS NOT NULL")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<StatusBoost> findPair(UUID actorId, UUID statusId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status b WHERE b.actor_id = :actor AND b.reblog_of_id = :status")
                .param("actor", actorId).param("status", statusId).query(MAPPER).optional();
    }

    public Optional<StatusBoost> findByUri(URI actorUri, URI uri) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status b JOIN actor a ON a.id = b.actor_id "
                        + "WHERE b.uri = :uri AND a.uri = :actor AND b.reblog_of_id IS NOT NULL")
                .param("uri", uri.toString()).param("actor", actorUri.toString()).query(MAPPER).optional();
    }

    /**
     * Keeps the boost's place in timelines when its server sends a newer {@code Announce} of the same status without
     * undoing the first, so that an {@code Undo} of either one finds it.
     */
    public void replaceActivity(UUID id, URI uri, Instant activityAt) {
        jdbc.sql("UPDATE status SET uri = :uri, activity_at = :at WHERE id = :id AND reblog_of_id IS NOT NULL")
                .param("id", id).param("uri", uri.toString()).param("at", activityAt.atOffset(ZoneOffset.UTC)).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM status WHERE id = :id AND reblog_of_id IS NOT NULL").param("id", id).update();
    }

    /**
     * Removes a remote boost by its {@code Announce}'s ID, which only its own actor can do.
     */
    public void deleteRemote(URI actorUri, URI uri) {
        jdbc.sql("""
                        DELETE FROM status s USING actor a
                        WHERE s.uri = :uri AND s.reblog_of_id IS NOT NULL AND a.id = s.actor_id AND a.uri = :actor
                          AND NOT a.is_local
                        """)
                .param("uri", uri.toString()).param("actor", actorUri.toString()).update();
    }

    /**
     * The boosts of a status by local accounts that haven't been deleted, which are sent an {@code Undo} when it goes.
     */
    public List<StatusBoost> findLocal(UUID statusId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status b JOIN actor a ON a.id = b.actor_id "
                        + "WHERE b.reblog_of_id = :status AND a.is_local AND a.deleted_at IS NULL")
                .param("status", statusId).query(MAPPER).list();
    }

    public Map<UUID, BoostStats> stats(Collection<UUID> statusIds, Optional<UUID> viewer) {
        if (statusIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                        SELECT b.reblog_of_id, count(*) AS total, coalesce(bool_or(b.actor_id = :viewer), false) AS reblogged
                        FROM status b JOIN actor a ON a.id = b.actor_id
                        WHERE b.reblog_of_id IN (:ids) AND
                        """ + LikeRepository.eligible("a") + " GROUP BY b.reblog_of_id")
                .param("ids", statusIds).param("viewer", viewer.orElse(null), Types.OTHER)
                .query((rs, row) -> Map.entry(rs.getObject("reblog_of_id", UUID.class),
                        new BoostStats(rs.getLong("total"), rs.getBoolean("reblogged"))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Counts the boosts that anyone can see, which are the ones a {@code shares} collection lists.
     */
    public long countPublic(UUID statusId) {
        return jdbc.sql("SELECT count(*) FROM status b JOIN actor a ON a.id = b.actor_id WHERE b.reblog_of_id = :status "
                        + "AND b.visibility IN ('public', 'unlisted') AND " + LikeRepository.eligible("a"))
                .param("status", statusId).query(Long.class).single();
    }

    /**
     * Lists the boosts that anyone can see, newest first.
     */
    public List<StatusBoost> pagePublic(UUID statusId, UUID maxId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM status b JOIN actor a ON a.id = b.actor_id "
                        + "WHERE b.reblog_of_id = :status AND b.visibility IN ('public', 'unlisted') AND "
                        + LikeRepository.eligible("a") + (maxId == null ? "" : " AND b.id < :max")
                        + " ORDER BY b.id DESC LIMIT :limit")
                .param("status", statusId).param("max", maxId, Types.OTHER).param("limit", limit).query(MAPPER).list();
    }
}
