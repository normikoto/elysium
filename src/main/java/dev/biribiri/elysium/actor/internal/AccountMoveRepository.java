package dev.biribiri.elysium.actor.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountMoveRepository {

    public record Row(UUID id, UUID actorId, UUID targetId, String activityUri, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    AccountMoveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return when it was saved, from the database's clock, or empty if a move by this activity was already saved
     */
    public Optional<Instant> insert(UUID id, UUID actorId, UUID targetId, String activityUri) {
        return jdbc.sql("""
                        INSERT INTO account_move (id, actor_id, target_id, activity_uri, created_at)
                        VALUES (:id, :actorId, :targetId, :activityUri, clock_timestamp())
                        ON CONFLICT (md5(activity_uri)) WHERE activity_uri IS NOT NULL DO NOTHING
                        RETURNING created_at
                        """)
                .param("id", id)
                .param("actorId", actorId)
                .param("targetId", targetId)
                .param("activityUri", activityUri)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    public Optional<Row> find(UUID id) {
        return jdbc.sql("SELECT id, actor_id, target_id, activity_uri, created_at FROM account_move WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getObject("target_id", UUID.class),
                        rs.getString("activity_uri"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public boolean existsForActivity(String activityUri) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account_move WHERE md5(activity_uri) = md5(:uri) AND activity_uri = :uri)")
                .param("uri", activityUri)
                .query(Boolean.class)
                .single();
    }

    /**
     * @return when the account last moved, if it did within this long
     */
    public Optional<Instant> latestWithin(UUID actorId, Duration within) {
        return jdbc.sql("""
                        SELECT max(created_at) FROM account_move
                        WHERE actor_id = :actorId AND created_at > now() - make_interval(secs => :seconds)
                        """)
                .param("actorId", actorId)
                .param("seconds", within.toSeconds())
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Deletes the moves an account made. Moves to it are kept, since they're the other account's, and its wait before
     * moving again comes from them.
     */
    public void deleteForActor(UUID actorId) {
        jdbc.sql("DELETE FROM account_move WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .update();
    }

    /**
     * Deletes a batch of the moves made by a server's accounts. Moves to them are kept, as for
     * {@link #deleteForActor}.
     *
     * @param domain as actors have it
     * @return how many were deleted, which is 0 once there are none left
     */
    public int deleteBatchForDomain(String domain, int limit) {
        return jdbc.sql("""
                        DELETE FROM account_move WHERE id IN (
                            SELECT m.id FROM account_move m
                            JOIN actor a ON a.id = m.actor_id
                            WHERE a.domain = :domain AND NOT a.is_local
                            LIMIT :limit
                        )
                        """)
                .param("domain", domain)
                .param("limit", limit)
                .update();
    }
}
