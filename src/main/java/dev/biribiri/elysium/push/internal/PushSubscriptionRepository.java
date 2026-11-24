package dev.biribiri.elysium.push.internal;

import dev.biribiri.elysium.push.PushPolicy;
import dev.biribiri.elysium.push.PushSubscription;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class PushSubscriptionRepository {

    /**
     * What sending needs, which clients are never shown again once they've subscribed.
     *
     * @param p256dh     the client's public key, as an uncompressed point
     * @param authSecret the secret it shares with this server
     */
    public record Stored(PushSubscription subscription, UUID actorId, String authorizationId, byte[] p256dh,
                         byte[] authSecret) {
    }

    private static final String COLUMNS = """
            id, actor_id, authorization_id, endpoint, p256dh, auth_secret, standard, alerts, policy""";

    private static final RowMapper<Stored> ROW = (row, n) -> new Stored(
            new PushSubscription(
                    row.getObject("id", UUID.class),
                    URI.create(row.getString("endpoint")),
                    row.getBoolean("standard"),
                    Set.of((String[]) row.getArray("alerts").getArray()),
                    PushPolicy.fromValue(row.getString("policy")).orElse(PushPolicy.NONE)),
            row.getObject("actor_id", UUID.class),
            row.getString("authorization_id"),
            row.getBytes("p256dh"),
            row.getBytes("auth_secret"));

    private final JdbcClient jdbc;

    PushSubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces whatever the grant had, with a new ID, as Mastodon does.
     */
    public void replace(UUID id, UUID actorId, String authorizationId, URI endpoint, byte[] p256dh, byte[] authSecret,
                        boolean standard, Collection<String> alerts, PushPolicy policy, Instant now) {
        // One statement, so that two requests at once with the same token can't both insert.
        jdbc.sql("""
                        INSERT INTO push_subscription (id, actor_id, authorization_id, endpoint, p256dh, auth_secret,
                                                       standard, alerts, policy, created_at)
                        VALUES (:id, :actorId, :authorizationId, :endpoint, :p256dh, :authSecret, :standard,
                                CAST(:alerts AS text[]), :policy, :now)
                        ON CONFLICT (authorization_id) DO UPDATE
                        SET id = excluded.id, endpoint = excluded.endpoint, p256dh = excluded.p256dh,
                            auth_secret = excluded.auth_secret, standard = excluded.standard, alerts = excluded.alerts,
                            policy = excluded.policy, created_at = excluded.created_at
                        """)
                .param("id", id)
                .param("actorId", actorId)
                .param("authorizationId", authorizationId)
                .param("endpoint", endpoint.toString())
                .param("p256dh", p256dh)
                .param("authSecret", authSecret)
                .param("standard", standard)
                .param("alerts", alerts.toArray(String[]::new))
                .param("policy", policy.value())
                .param("now", Timestamp.from(now))
                .update();
    }

    public boolean update(String authorizationId, Collection<String> alerts, PushPolicy policy) {
        return jdbc.sql("""
                        UPDATE push_subscription SET alerts = CAST(:alerts AS text[]), policy = :policy
                        WHERE authorization_id = :authorizationId
                        """)
                .param("alerts", alerts.toArray(String[]::new))
                .param("policy", policy.value())
                .param("authorizationId", authorizationId)
                .update() > 0;
    }

    public Optional<Stored> findByAuthorization(String authorizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscription WHERE authorization_id = :authorizationId")
                .param("authorizationId", authorizationId)
                .query(ROW)
                .optional();
    }

    public Optional<Stored> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscription WHERE id = :id")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /**
     * @return the IDs of the actor's subscriptions that want this type
     */
    public List<UUID> findWanting(UUID actorId, String type) {
        return jdbc.sql("""
                        SELECT id FROM push_subscription
                        WHERE actor_id = :actorId AND :type = ANY (alerts) AND policy <> 'none'
                        """)
                .param("actorId", actorId)
                .param("type", type)
                .query(UUID.class)
                .list();
    }

    public void removeForAuthorization(String authorizationId) {
        jdbc.sql("DELETE FROM push_subscription WHERE authorization_id = :authorizationId")
                .param("authorizationId", authorizationId)
                .update();
    }

    public void remove(UUID id) {
        jdbc.sql("DELETE FROM push_subscription WHERE id = :id").param("id", id).update();
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM push_subscription WHERE actor_id = :actorId").param("actorId", actorId).update();
    }
}
