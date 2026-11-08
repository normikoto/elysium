package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.StatusLike;
import dev.biribiri.elysium.content.LikeStats;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class LikeRepository {
    private static final RowMapper<StatusLike> MAPPER = (rs, row) -> new StatusLike(
            rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class), rs.getObject("status_id", UUID.class),
            rs.getString("activity_uri"), rs.getObject("activity_at", OffsetDateTime.class).toInstant());

    /** Hides suspended and deleted actors, and those on blocked domains, so that they come back if that ends. */
    static String eligible(String actor) {
        var host = "regexp_replace(" + actor + ".domain, ':[0-9]+$', '')";
        return actor + ".suspended_at IS NULL AND " + actor + ".deleted_at IS NULL AND (" + actor
                + ".is_local OR NOT EXISTS (SELECT 1 FROM domain_block b WHERE " + host + " = b.domain OR right("
                + host + ", length(b.domain) + 1) = '.' || b.domain))";
    }

    private final JdbcClient jdbc;

    LikeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockPair(String actorUri, UUID statusId) {
        lock("like\n" + actorUri + "\n" + statusId);
    }

    public void lockActivity(String uri) {
        lock("like-activity\n" + uri);
    }

    private void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key).query((rs, row) -> true).single();
    }

    /** Hold actors before statuses and likes, in the order used by cascading deletion. */
    public void holdActors(Collection<UUID> ids) {
        jdbc.sql("SELECT id FROM actor WHERE id IN (:ids) ORDER BY id FOR KEY SHARE")
                .param("ids", ids).query(UUID.class).list();
    }

    public boolean eligibleActor(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM actor a WHERE a.id = :id AND " + eligible("a") + ")")
                .param("id", id).query(Boolean.class).single();
    }

    public Optional<UUID> localStatusId(URI uri) {
        return jdbc.sql("SELECT s.id FROM status s JOIN actor a ON a.id = s.actor_id WHERE s.uri = :uri AND a.is_local "
                        + "AND s.reblog_of_id IS NULL")
                .param("uri", uri.toString()).query(UUID.class).optional();
    }

    public boolean holdStatus(UUID id) {
        return jdbc.sql("SELECT id FROM status WHERE id = :id FOR KEY SHARE")
                .param("id", id).query(UUID.class).optional().isPresent();
    }

    public Optional<StatusLike> find(UUID id) {
        return jdbc.sql("SELECT * FROM status_like WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<StatusLike> findPair(UUID actorId, UUID statusId) {
        return jdbc.sql("SELECT * FROM status_like WHERE actor_id = :actor AND status_id = :status")
                .param("actor", actorId).param("status", statusId).query(MAPPER).optional();
    }

    public Optional<StatusLike> findActivity(String actorUri, String activityUri) {
        return jdbc.sql("""
                        SELECT l.* FROM status_like l JOIN actor a ON a.id = l.actor_id
                        WHERE a.uri = :actor AND md5(l.activity_uri) = md5(:activity) AND l.activity_uri = :activity
                        """).param("actor", actorUri).param("activity", activityUri).query(MAPPER).optional();
    }

    public StatusLike insert(UUID actorId, UUID statusId, String activityUri, Instant activityAt) {
        var like = new StatusLike(Ids.timeOrdered(Instant.now()), actorId, statusId, activityUri, activityAt);
        jdbc.sql("""
                        INSERT INTO status_like (id, actor_id, status_id, activity_uri, activity_at)
                        VALUES (:id, :actor, :status, :activity, :at)
                        """).param("id", like.id()).param("actor", actorId).param("status", statusId)
                .param("activity", activityUri).param("at", time(activityAt)).update();
        return like;
    }

    /** Keep the source identity when a server sends another Like without an intervening Undo. */
    public void replaceActivity(UUID id, String activityUri, Instant activityAt) {
        jdbc.sql("UPDATE status_like SET activity_uri = :activity, activity_at = :at WHERE id = :id")
                .param("id", id).param("activity", activityUri).param("at", time(activityAt)).update();
    }

    public void deleteActivity(String actorUri, String activityUri) {
        jdbc.sql("""
                        DELETE FROM status_like l USING actor a WHERE l.actor_id = a.id AND a.uri = :actor
                        AND md5(l.activity_uri) = md5(:activity) AND l.activity_uri = :activity
                        """).param("actor", actorUri).param("activity", activityUri).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM status_like WHERE id = :id").param("id", id).update();
    }

    public boolean wasUndone(String activity, String actor, UUID status, Instant firstArrival) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM undone_like WHERE md5(activity_uri) = md5(:activity)
                            AND activity_uri = :activity AND md5(actor_uri) = md5(:actor) AND actor_uri = :actor)
                        OR EXISTS (SELECT 1 FROM undone_like_pair WHERE md5(actor_uri) = md5(:actor)
                            AND actor_uri = :actor AND status_id = :status AND undone_at >= :at)
                        """).param("activity", activity).param("actor", actor).param("status", status)
                .param("at", time(firstArrival)).query(Boolean.class).single();
    }

    public void markUndone(String activity, String actor, Instant at) {
        jdbc.sql("""
                        INSERT INTO undone_like (activity_uri, actor_uri, undone_at) VALUES (:activity, :actor, :at)
                        ON CONFLICT (md5(activity_uri), md5(actor_uri))
                        DO UPDATE SET undone_at = greatest(undone_like.undone_at, EXCLUDED.undone_at)
                        """).param("activity", activity).param("actor", actor).param("at", time(at)).update();
    }

    public void cover(String actor, UUID status, Instant at) {
        jdbc.sql("""
                        INSERT INTO undone_like_pair (actor_uri, status_id, undone_at) VALUES (:actor, :status, :at)
                        ON CONFLICT (md5(actor_uri), status_id)
                        DO UPDATE SET undone_at = greatest(undone_like_pair.undone_at, EXCLUDED.undone_at)
                        """).param("actor", actor).param("status", status).param("at", time(at)).update();
        jdbc.sql("""
                        DELETE FROM status_like l USING actor a WHERE l.actor_id = a.id AND a.uri = :actor
                        AND l.status_id = :status AND l.activity_at <= :at
                        """).param("actor", actor).param("status", status).param("at", time(at)).update();
    }

    public Map<UUID, LikeStats> stats(Collection<UUID> statusIds, Optional<UUID> viewer) {
        if (statusIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                        SELECT l.status_id, count(*) AS total, coalesce(bool_or(l.actor_id = :viewer), false) AS liked
                        FROM status_like l JOIN actor a ON a.id = l.actor_id
                        WHERE l.status_id IN (:ids) AND
                        """ + eligible("a") + " GROUP BY l.status_id")
                .param("ids", statusIds).param("viewer", viewer.orElse(null), Types.OTHER)
                .query((rs, row) -> Map.entry(rs.getObject("status_id", UUID.class),
                        new LikeStats(rs.getLong("total"), rs.getBoolean("liked"))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<StatusLike> page(UUID statusId, UUID maxId, int limit) {
        return jdbc.sql("SELECT l.* FROM status_like l JOIN actor a ON a.id = l.actor_id WHERE l.status_id = :status AND "
                        + eligible("a") + (maxId == null ? "" : " AND l.id < :max") + " ORDER BY l.id DESC LIMIT :limit")
                .param("status", statusId).param("max", maxId, Types.OTHER).param("limit", limit).query(MAPPER).list();
    }

    public void removeForActor(UUID actor) {
        jdbc.sql("DELETE FROM status_like WHERE actor_id = :actor").param("actor", actor).update();
    }

    public void removeForActors(Collection<UUID> actors) {
        jdbc.sql("DELETE FROM status_like WHERE actor_id IN (:actors)").param("actors", actors).update();
    }

    public List<UUID> actorsOn(String domain, UUID after, int limit) {
        return jdbc.sql("SELECT id FROM actor WHERE NOT is_local AND domain = :domain AND id > :after ORDER BY id LIMIT :limit")
                .param("domain", domain).param("after", after).param("limit", limit).query(UUID.class).list();
    }

    private static OffsetDateTime time(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

}
