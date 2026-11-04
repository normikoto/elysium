package dev.biribiri.elysium.graph.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class FollowRepository {

    private final JdbcClient jdbc;

    FollowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes others working on the same activity wait until this transaction ends.
     */
    public void lockActivity(String activityUri) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:uri, 0))")
                .param("uri", activityUri)
                .query(rs -> {
                });
    }

    public void saveAccepted(UUID followerId, UUID followedId, String activityUri) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :followerId, :followedId, 'accepted', :activityUri)
                        ON CONFLICT (follower_id, followed_id)
                        DO UPDATE SET state = 'accepted', activity_uri = EXCLUDED.activity_uri
                        """)
                .param("id", UUID.randomUUID())
                .param("followerId", followerId)
                .param("followedId", followedId)
                .param("activityUri", activityUri)
                .update();
    }

    public void deleteByActivity(UUID followerId, String activityUri) {
        jdbc.sql("DELETE FROM follow WHERE follower_id = :followerId AND activity_uri = :activityUri")
                .param("followerId", followerId)
                .param("activityUri", activityUri)
                .update();
    }

    public void deleteByPair(UUID followerId, UUID followedId) {
        jdbc.sql("DELETE FROM follow WHERE follower_id = :followerId AND followed_id = :followedId")
                .param("followerId", followerId)
                .param("followedId", followedId)
                .update();
    }

    public boolean isUndone(String activityUri) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM undone_follow WHERE activity_uri = :activityUri)")
                .param("activityUri", activityUri)
                .query(Boolean.class)
                .single();
    }

    public void unmarkUndone(String activityUri) {
        jdbc.sql("DELETE FROM undone_follow WHERE activity_uri = :activityUri")
                .param("activityUri", activityUri)
                .update();
    }

    public void markUndone(String activityUri) {
        jdbc.sql("INSERT INTO undone_follow (activity_uri) VALUES (:activityUri) ON CONFLICT DO NOTHING")
                .param("activityUri", activityUri)
                .update();
    }
}
