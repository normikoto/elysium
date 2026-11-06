package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.graph.FollowCounts;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
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
public class FollowRepository {

    private static final RowMapper<Row> ROW_MAPPER = (rs, rowNum) -> new Row(
            rs.getObject("followed_id", UUID.class),
            rs.getString("activity_uri"),
            rs.getObject("activity_at", OffsetDateTime.class).toInstant(),
            "accepted".equals(rs.getString("state")));

    /**
     * Leaves out follows whose other actor, joined as {@code a}, is suspended or deleted.
     */
    private static final String ACTIVE_OTHER = "a.suspended_at IS NULL AND a.deleted_at IS NULL";

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
                        SELECT followed_id, activity_uri, activity_at, state FROM follow
                        WHERE follower_id = :followerId AND followed_id = :followedId
                        """)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<Row> findByActivity(UUID followerId, String activityUri) {
        return jdbc.sql("""
                        SELECT followed_id, activity_uri, activity_at, state FROM follow
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
     * Inserts a follow by a local account, unless the two already have one.
     *
     * @param activityUri the {@code Follow} sent for it, which leaves it waiting for an answer, or null for a follow of a
     *                    local account, which is accepted straight away
     * @return whether it was inserted
     */
    public boolean insertOutgoing(UUID id, UUID followerId, UUID followedId, String activityUri) {
        return jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :followerId, :followedId, :state, :activityUri)
                        ON CONFLICT (follower_id, followed_id) DO NOTHING
                        """)
                .param("id", id)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .param("state", activityUri == null ? "accepted" : "pending")
                .param("activityUri", activityUri, Types.VARCHAR)
                .update() > 0;
    }

    /**
     * @return the removed follow, if there was one
     */
    public Optional<Row> deletePair(UUID followerId, UUID followedId) {
        return jdbc.sql("""
                        DELETE FROM follow WHERE follower_id = :followerId AND followed_id = :followedId
                        RETURNING followed_id, activity_uri, activity_at, state
                        """)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * Accepts a follow that's waiting for an answer from the actor followed.
     *
     * @return whether one was waiting
     */
    public boolean acceptOutgoing(UUID followedId, String activityUri) {
        return jdbc.sql("""
                        UPDATE follow SET state = 'accepted'
                        WHERE followed_id = :followedId AND activity_uri = :activityUri AND state = 'pending'
                        """)
                .param("followedId", followedId)
                .param("activityUri", activityUri)
                .update() > 0;
    }

    /**
     * Like {@link #acceptOutgoing(UUID, String)}, for whatever follow the two actors have.
     */
    public boolean acceptOutgoing(UUID followerId, UUID followedId) {
        return jdbc.sql("""
                        UPDATE follow SET state = 'accepted'
                        WHERE follower_id = :followerId AND followed_id = :followedId AND state = 'pending'
                        """)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .update() > 0;
    }

    /**
     * @return whether a follow was removed
     */
    public boolean deleteOutgoing(UUID followedId, String activityUri) {
        return jdbc.sql("DELETE FROM follow WHERE followed_id = :followedId AND activity_uri = :activityUri")
                .param("followedId", followedId)
                .param("activityUri", activityUri)
                .update() > 0;
    }

    /**
     * @return whether a follow with this activity behind it exists, accepted or not
     */
    public boolean existsOutgoing(UUID followerId, UUID followedId, String activityUri) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM follow
                            WHERE follower_id = :followerId AND followed_id = :followedId AND activity_uri = :activityUri
                        )
                        """)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .param("activityUri", activityUri)
                .query(Boolean.class)
                .single();
    }

    /**
     * @return every follow, accepted or not, from the viewer to any of these accounts, or from any of them to the viewer
     */
    public List<Pair> findPairsWith(UUID viewerId, Collection<UUID> otherIds) {
        if (otherIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT follower_id, followed_id, state FROM follow
                        WHERE (follower_id = :viewerId AND followed_id IN (:otherIds))
                           OR (followed_id = :viewerId AND follower_id IN (:otherIds))
                        """)
                .param("viewerId", viewerId)
                .param("otherIds", otherIds)
                .query((rs, row) -> new Pair(
                        rs.getObject("follower_id", UUID.class),
                        rs.getObject("followed_id", UUID.class),
                        "accepted".equals(rs.getString("state"))))
                .list();
    }

    public boolean isFollowing(UUID followerId, UUID followedId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM follow
                            WHERE follower_id = :followerId AND followed_id = :followedId AND state = 'accepted'
                        )
                        """)
                .param("followerId", followerId)
                .param("followedId", followedId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Lists an account's accepted followers, or the accounts it follows, leaving out suspended and deleted ones.
     *
     * @param followers true for its followers, and false for the accounts it follows
     */
    public List<FollowEntry> findPage(UUID actorId, boolean followers, FollowPage page) {
        var self = followers ? "f.followed_id" : "f.follower_id";
        var other = followers ? "f.follower_id" : "f.followed_id";
        var sql = new StringBuilder("SELECT f.id, a.id AS other_id, a.uri AS other_uri FROM follow f JOIN actor a ON a.id = ")
                .append(other).append(" WHERE ").append(self).append(" = :actorId AND f.state = 'accepted' AND ")
                .append(ACTIVE_OTHER);
        var parameters = new HashMap<String, Object>();
        parameters.put("actorId", actorId);
        // A cursor whose follow has since been removed matches nothing, so its page is empty.
        if (page.maxId() != null) {
            sql.append(" AND (f.created_at, f.id) < (SELECT created_at, id FROM follow WHERE id = :maxId)");
            parameters.put("maxId", page.maxId());
        }
        var ascending = page.minId() != null;
        if (ascending) {
            sql.append(" AND (f.created_at, f.id) > (SELECT created_at, id FROM follow WHERE id = :minId)");
            parameters.put("minId", page.minId());
        } else if (page.sinceId() != null) {
            sql.append(" AND (f.created_at, f.id) > (SELECT created_at, id FROM follow WHERE id = :sinceId)");
            parameters.put("sinceId", page.sinceId());
        }
        sql.append(ascending ? " ORDER BY f.created_at ASC, f.id ASC" : " ORDER BY f.created_at DESC, f.id DESC")
                .append(" LIMIT :limit");
        parameters.put("limit", page.limit());
        var found = jdbc.sql(sql.toString())
                .params(parameters)
                .query((rs, row) -> new FollowEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("other_id", UUID.class),
                        URI.create(rs.getString("other_uri"))))
                .list();
        return ascending ? found.reversed() : found;
    }

    /**
     * Counts the accepted follows to and from each of these accounts, leaving out suspended and deleted actors.
     *
     * @return an entry for each account
     */
    public Map<UUID, FollowCounts> countFor(Collection<UUID> actorIds) {
        var followers = count(actorIds, true);
        var following = count(actorIds, false);
        var counts = new HashMap<UUID, FollowCounts>();
        for (var id : actorIds) {
            counts.put(id, new FollowCounts(followers.getOrDefault(id, 0L), following.getOrDefault(id, 0L)));
        }
        return counts;
    }

    /**
     * Counts the accepted followers of each of these accounts, or the accounts each follows, leaving out suspended and
     * deleted actors.
     *
     * @param followers true for followers, and false for the accounts followed
     * @return an entry for each account with any
     */
    public Map<UUID, Long> count(Collection<UUID> actorIds, boolean followers) {
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        var self = followers ? "f.followed_id" : "f.follower_id";
        var other = followers ? "f.follower_id" : "f.followed_id";
        var counts = new HashMap<UUID, Long>();
        jdbc.sql("SELECT " + self + " AS id, count(*) AS n FROM follow f JOIN actor a ON a.id = " + other
                        + " WHERE " + self + " IN (:ids) AND f.state = 'accepted' AND " + ACTIVE_OTHER
                        + " GROUP BY " + self)
                .param("ids", actorIds)
                .query(rs -> {
                    counts.put(rs.getObject("id", UUID.class), rs.getLong("n"));
                });
        return counts;
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

    /**
     * @param accepted whether the follow is accepted, rather than waiting for an answer
     */
    public record Row(UUID followedId, String activityUri, Instant activityAt, boolean accepted) {
    }

    /**
     * @param accepted whether the follow is accepted, rather than waiting for an answer
     */
    public record Pair(UUID followerId, UUID followedId, boolean accepted) {
    }
}
