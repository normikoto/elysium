package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.StatusReaction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Locks are taken in the order {@link LikeRepository} takes them: actors, then the status, then the reaction.
 */
@Repository
public class ReactionRepository {

    private static final RowMapper<StatusReaction> MAPPER = ReactionRepository::map;

    private final JdbcClient jdbc;

    ReactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param emojiId null for a Unicode emoji
     */
    public void lockReaction(String actorUri, UUID statusId, String name, UUID emojiId) {
        lock("reaction\n" + actorUri + "\n" + statusId + "\n" + name + "\n" + emojiId);
    }

    /**
     * Held while counting an actor's reactions to a status, so that two at once can't both fit under the limit.
     */
    public void lockActorOnStatus(String actorUri, UUID statusId) {
        lock("reactions\n" + actorUri + "\n" + statusId);
    }

    public void lockActivity(String uri) {
        lock("reaction-activity\n" + uri);
    }

    private void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key).query((rs, row) -> true).single();
    }

    public Optional<StatusReaction> find(UUID id) {
        return jdbc.sql("SELECT * FROM status_reaction WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<StatusReaction> findOne(UUID actorId, UUID statusId, String name, UUID emojiId) {
        return jdbc.sql("""
                        SELECT * FROM status_reaction WHERE actor_id = :actor AND status_id = :status AND name = :name
                        AND emoji_id IS NOT DISTINCT FROM :emoji
                        """)
                .param("actor", actorId).param("status", statusId).param("name", name)
                .param("emoji", emojiId, Types.OTHER)
                .query(MAPPER).optional();
    }

    public int countFor(UUID actorId, UUID statusId) {
        return jdbc.sql("SELECT count(*) FROM status_reaction WHERE actor_id = :actor AND status_id = :status")
                .param("actor", actorId).param("status", statusId).query(Integer.class).single();
    }

    public List<StatusReaction> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM status_reaction WHERE id IN (:ids)").param("ids", ids).query(MAPPER).list();
    }

    public Optional<StatusReaction> findActivity(String actorUri, String activityUri) {
        return jdbc.sql("""
                        SELECT r.* FROM status_reaction r JOIN actor a ON a.id = r.actor_id
                        WHERE a.uri = :actor AND md5(r.activity_uri) = md5(:activity) AND r.activity_uri = :activity
                        """).param("actor", actorUri).param("activity", activityUri).query(MAPPER).optional();
    }

    public StatusReaction insert(UUID actorId, UUID statusId, String name, UUID emojiId, String activityUri,
                                 Instant activityAt) {
        var reaction = new StatusReaction(Ids.timeOrdered(Instant.now()), actorId, statusId, name, emojiId, activityUri,
                activityAt);
        jdbc.sql("""
                        INSERT INTO status_reaction (id, actor_id, status_id, name, emoji_id, activity_uri, activity_at)
                        VALUES (:id, :actor, :status, :name, :emoji, :activity, :at)
                        """)
                .param("id", reaction.id()).param("actor", actorId).param("status", statusId).param("name", name)
                .param("emoji", emojiId, Types.OTHER).param("activity", activityUri).param("at", time(activityAt))
                .update();
        return reaction;
    }

    /**
     * Keeps the newest identity when a server sends the same reaction again without an {@code Undo} in between.
     */
    public void replaceActivity(UUID id, String activityUri, Instant activityAt) {
        jdbc.sql("UPDATE status_reaction SET activity_uri = :activity, activity_at = :at WHERE id = :id")
                .param("id", id).param("activity", activityUri).param("at", time(activityAt)).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM status_reaction WHERE id = :id").param("id", id).update();
    }

    /**
     * By the activity's identity rather than a row's ID, since another delivery can replace the row meanwhile.
     */
    public void deleteActivity(String actorUri, String activityUri) {
        jdbc.sql("""
                        DELETE FROM status_reaction r USING actor a WHERE r.actor_id = a.id AND a.uri = :actor
                        AND md5(r.activity_uri) = md5(:activity) AND r.activity_uri = :activity
                        """).param("actor", actorUri).param("activity", activityUri).update();
    }

    public boolean wasUndone(String activityUri, String actorUri) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM undone_reaction WHERE md5(activity_uri) = md5(:activity)
                            AND activity_uri = :activity AND md5(actor_uri) = md5(:actor) AND actor_uri = :actor)
                        """).param("activity", activityUri).param("actor", actorUri).query(Boolean.class).single();
    }

    public void markUndone(String activityUri, String actorUri, Instant at) {
        jdbc.sql("""
                        INSERT INTO undone_reaction (activity_uri, actor_uri, undone_at) VALUES (:activity, :actor, :at)
                        ON CONFLICT (md5(activity_uri), md5(actor_uri))
                        DO UPDATE SET undone_at = greatest(undone_reaction.undone_at, EXCLUDED.undone_at)
                        """).param("activity", activityUri).param("actor", actorUri).param("at", time(at)).update();
    }

    /**
     * One row for each emoji, from actors that can be shown, with the most recent of them first in {@code actor_ids} up
     * to a limit.
     */
    public record Summary(UUID statusId, String name, UUID emojiId, long count, boolean me, List<UUID> actorIds) {
    }

    /**
     * Ordered by when each emoji was first used on a status, as Pleroma orders them.
     *
     * @param accountsEach how many of each emoji's actors to list, which can be 0
     */
    public List<Summary> summaries(Collection<UUID> statusIds, Optional<UUID> viewer, int accountsEach) {
        if (statusIds.isEmpty()) {
            return List.of();
        }
        // Statuses are shown without their accounts, so those aren't gathered unless they're asked for.
        var actorIds = accountsEach == 0 ? "CAST(ARRAY[] AS uuid[])" : """
                trim_array(array_agg(r.actor_id ORDER BY r.id DESC),
                           CAST(greatest(count(*) - :accounts, 0) AS int))""";
        return jdbc.sql("""
                        SELECT r.status_id, r.name, r.emoji_id, count(*) AS total,
                               coalesce(bool_or(r.actor_id = :viewer), false) AS me,
                        """ + actorIds + """
                         AS actor_ids
                        FROM status_reaction r JOIN actor a ON a.id = r.actor_id
                        WHERE r.status_id IN (:ids) AND
                        """ + LikeRepository.eligible("a") + """
                         GROUP BY r.status_id, r.name, r.emoji_id
                        ORDER BY r.status_id, min(r.activity_at), r.name
                        """)
                .param("ids", statusIds).param("viewer", viewer.orElse(null), Types.OTHER)
                .param("accounts", accountsEach)
                .query((rs, row) -> new Summary(
                        rs.getObject("status_id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("emoji_id", UUID.class),
                        rs.getLong("total"),
                        rs.getBoolean("me"),
                        rs.getArray("actor_ids") == null ? List.of() : List.of((UUID[]) rs.getArray("actor_ids").getArray())))
                .list();
    }

    /**
     * A local account's reaction that's been removed with its emoji, with what its {@code Undo} needs.
     */
    public record Withdrawn(UUID actorId, UUID statusId, UUID authorId, URI statusUri, String activityUri, String name,
                            UUID emojiId) {
    }

    /**
     * Removes the reactions with a custom emoji that's about to be removed, returning local accounts' ones, whose
     * withdrawal has to be sent. Other servers' reactions with it go with it.
     */
    public List<Withdrawn> withdrawWith(Collection<UUID> emojiIds) {
        if (emojiIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        WITH removed AS (
                            DELETE FROM status_reaction r WHERE r.emoji_id IN (:emojis)
                            RETURNING r.actor_id, r.status_id, r.activity_uri, r.name, r.emoji_id
                        )
                        SELECT removed.*, s.actor_id AS author_id, s.uri AS status_uri
                        FROM removed JOIN actor a ON a.id = removed.actor_id JOIN status s ON s.id = removed.status_id
                        WHERE a.is_local
                        """)
                .param("emojis", emojiIds)
                .query((rs, row) -> new Withdrawn(
                        rs.getObject("actor_id", UUID.class),
                        rs.getObject("status_id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        URI.create(rs.getString("status_uri")),
                        rs.getString("activity_uri"),
                        rs.getString("name"),
                        rs.getObject("emoji_id", UUID.class)))
                .list();
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM status_reaction WHERE actor_id = :actor").param("actor", actorId).update();
    }

    public void removeForActors(Collection<UUID> actorIds) {
        jdbc.sql("DELETE FROM status_reaction WHERE actor_id IN (:actors)").param("actors", actorIds).update();
    }

    private static StatusReaction map(ResultSet rs, int row) throws SQLException {
        return new StatusReaction(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getObject("status_id", UUID.class),
                rs.getString("name"),
                rs.getObject("emoji_id", UUID.class),
                rs.getString("activity_uri"),
                rs.getObject("activity_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime time(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
