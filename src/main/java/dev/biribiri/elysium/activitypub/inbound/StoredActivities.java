package dev.biribiri.elysium.activitypub.inbound;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Reads the activities that {@link InboundActivities} stored. This is apart from it because handlers read these, and it
 * depends on the handlers.
 */
@Component
class StoredActivities {

    private final JdbcClient jdbc;

    StoredActivities(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean exists(String id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM inbound_activity WHERE activity_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    Optional<InboundActivity> find(String id) {
        return jdbc.sql("""
                        SELECT actor_uri, type, body, first_received_at, received_at
                        FROM inbound_activity WHERE activity_id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> new InboundActivity(
                        id,
                        rs.getString("actor_uri"),
                        rs.getString("type"),
                        rs.getString("body"),
                        rs.getObject("first_received_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("received_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
