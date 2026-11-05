package dev.biribiri.elysium.graph.internal;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FollowRepository {

    private static final RowMapper<Row> ROW_MAPPER = (rs, rowNum) -> new Row(
            rs.getObject("followed_id", UUID.class),
            rs.getString("activity_uri"),
            rs.getObject("activity_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    FollowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes others using the same key wait until this transaction ends.
     */
    public void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key)
                .query(rs -> {
                });
    }

    public Optional<Row> findByPair(UUID followerId, UUID followedId) {
        return jdbc.sql("""
                        SELECT followed_id, activity_uri, activity_at FROM follow
                        WHERE follower_id = :followerId AND followed_id = :followedId
                        """)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<Row> findByActivity(UUID followerId, String activityUri) {
        return jdbc.sql("""
                        SELECT followed_id, activity_uri, activity_at FROM follow
                        WHERE follower_id = :followerId AND activity_uri = :activityUri
                        ORDER BY activity_at DESC, id
                        LIMIT 1
                        """)
                .param("followerId", followerId)
                .param("activityUri", activityUri)
                .query(ROW_MAPPER)
                .optional();
    }

    public void saveAccepted(UUID followerId, UUID followedId, String activityUri, Instant activityAt) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri, activity_at)
                        VALUES (:id, :followerId, :followedId, 'accepted', :activityUri, :activityAt)
                        ON CONFLICT (follower_id, followed_id)
                        DO UPDATE SET state = 'accepted', activity_uri = EXCLUDED.activity_uri, activity_at = EXCLUDED.activity_at
                        """)
                .param("id", UUID.randomUUID())
                .param("followerId", followerId)
                .param("followedId", followedId)
                .param("activityUri", activityUri)
                .param("activityAt", time(activityAt))
                .update();
    }

    /**
     * @return one inbox for each server or actor to deliver to, using a server's shared inbox for the followers that have one
     */
    public List<URI> findFollowerInboxes(UUID followedId) {
        return jdbc.sql("""
                        SELECT DISTINCT COALESCE(a.shared_inbox_uri, a.inbox_uri)
                        FROM follow f JOIN actor a ON a.id = f.follower_id
                        WHERE f.followed_id = :followedId AND f.state = 'accepted' AND NOT a.is_local
                        """)
                .param("followedId", followedId)
                .query((rs, row) -> URI.create(rs.getString(1)))
                .list();
    }

    /**
     * Like {@link #findFollowerInboxes}, leaving out followers who started following after this time. A newer {@code Follow}
     * from a follower updates the same row and keeps {@code created_at}, so that's when they started following.
     */
    public List<URI> findFollowerInboxesAsOf(UUID followedId, Instant time) {
        return jdbc.sql("""
                        SELECT DISTINCT COALESCE(a.shared_inbox_uri, a.inbox_uri)
                        FROM follow f JOIN actor a ON a.id = f.follower_id
                        WHERE f.followed_id = :followedId AND f.state = 'accepted' AND NOT a.is_local
                          AND f.created_at <= :time
                        """)
                .param("followedId", followedId)
                .param("time", time(time))
                .query((rs, row) -> URI.create(rs.getString(1)))
                .list();
    }

    public long countFollowers(UUID followedId) {
        return jdbc.sql("SELECT count(*) FROM follow WHERE followed_id = :followedId AND state = 'accepted'")
                .param("followedId", followedId)
                .query(Long.class)
                .single();
    }

    /**
     * @return who the removed follow was of, if one was removed
     */
    public Optional<UUID> deleteByActivity(UUID followerId, String activityUri) {
        return jdbc.sql("DELETE FROM follow WHERE follower_id = :followerId AND activity_uri = :activityUri RETURNING followed_id")
                .param("followerId", followerId)
                .param("activityUri", activityUri)
                .query(UUID.class)
                .list()
                .stream()
                .findFirst();
    }

    /**
     * @return the activity behind the removed follow, if one was removed
     */
    public Optional<String> deleteByPair(UUID followerId, UUID followedId, Instant arrivedBy) {
        return jdbc.sql("""
                        DELETE FROM follow
                        WHERE follower_id = :followerId AND followed_id = :followedId AND activity_at <= :arrivedBy
                        RETURNING activity_uri
                        """)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .param("arrivedBy", time(arrivedBy))
                .query(String.class)
                .optional();
    }

    /**
     * @return the domain of every remote actor
     */
    public List<String> findRemoteDomains() {
        return jdbc.sql("SELECT DISTINCT domain FROM actor WHERE NOT is_local").query(String.class).list();
    }

    /**
     * @param after only return IDs that sort after this one, in PostgreSQL's order
     * @return the IDs of remote actors on a domain, in order
     */
    public List<UUID> findRemoteActorIds(String domain, UUID after, int limit) {
        return jdbc.sql("""
                        SELECT id FROM actor
                        WHERE domain = :domain AND NOT is_local AND id > :after
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("domain", domain)
                .param("after", after)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public void deleteForActors(Collection<UUID> actorIds) {
        jdbc.sql("DELETE FROM follow WHERE follower_id IN (:ids) OR followed_id IN (:ids)")
                .param("ids", actorIds)
                .update();
    }

    public boolean isUndone(String activityUri, String followerUri) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM undone_follow
                            WHERE md5(activity_uri) = md5(:activityUri) AND md5(follower_uri) = md5(:followerUri)
                              AND activity_uri = :activityUri AND follower_uri = :followerUri
                        )
                        """)
                .param("activityUri", activityUri)
                .param("followerUri", followerUri)
                .query(Boolean.class)
                .single();
    }

    public void markUndone(String activityUri, String followerUri, Instant undoneAt) {
        jdbc.sql("""
                        INSERT INTO undone_follow (activity_uri, follower_uri, undone_at)
                        VALUES (:activityUri, :followerUri, :undoneAt)
                        ON CONFLICT (md5(activity_uri), md5(follower_uri))
                        DO UPDATE SET undone_at = GREATEST(undone_follow.undone_at, EXCLUDED.undone_at)
                        """)
                .param("activityUri", activityUri)
                .param("followerUri", followerUri)
                .param("undoneAt", time(undoneAt))
                .update();
    }

    /**
     * @return whether an {@code Undo} covers an activity for these two actors that arrived at this time
     */
    public boolean isPairUndone(String followerUri, UUID followedId, Instant activityAt) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM undone_follow_pair
                            WHERE md5(follower_uri) = md5(:followerUri) AND followed_id = :followedId
                              AND follower_uri = :followerUri AND undone_at >= :activityAt
                        )
                        """)
                .param("followerUri", followerUri)
                .param("followedId", followedId)
                .param("activityAt", time(activityAt))
                .query(Boolean.class)
                .single();
    }

    public void markPairUndone(String followerUri, UUID followedId, Instant undoneAt) {
        jdbc.sql("""
                        INSERT INTO undone_follow_pair (follower_uri, followed_id, undone_at)
                        VALUES (:followerUri, :followedId, :undoneAt)
                        ON CONFLICT (md5(follower_uri), followed_id)
                        DO UPDATE SET undone_at = GREATEST(undone_follow_pair.undone_at, EXCLUDED.undone_at)
                        """)
                .param("followerUri", followerUri)
                .param("followedId", followedId)
                .param("undoneAt", time(undoneAt))
                .update();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record Row(UUID followedId, String activityUri, Instant activityAt) {
    }
}
