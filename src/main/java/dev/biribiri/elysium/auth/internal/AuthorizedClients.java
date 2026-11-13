package dev.biribiri.elysium.auth.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Keeps track of which clients have ever been authorized, so that {@link UnauthorizedClientCleanupTask} can remove the
 * ones that never were.
 */
@Component
class AuthorizedClients {

    /**
     * How long someone has after registering an app to log in with it.
     */
    static final Duration GRACE_PERIOD = Duration.ofDays(1);

    private final JdbcClient jdbc;

    AuthorizedClients(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that a client was authorized. This has to run in the same transaction as saving what authorized it, so that
     * the foreign key refuses to authorize a client that a cleanup has removed. If the two happen at the same moment, the
     * cleanup can still win, and then removes what was just saved along with the client.
     */
    void markAuthorized(String registeredClientId) {
        jdbc.sql("""
                        INSERT INTO authorized_client (registered_client_id) VALUES (:id)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", registeredClientId)
                .update();
    }

    /**
     * Removes clients that were registered more than {@link #GRACE_PERIOD} ago and never authorized, with any
     * authorization requests that were started for them and never finished.
     *
     * @return how many clients were removed
     */
    @Transactional
    int removeUnauthorized() {
        var removed = jdbc.sql("""
                        DELETE FROM oauth2_registered_client c
                        WHERE c.client_id_issued_at <= :cutoff
                          AND NOT EXISTS (SELECT 1 FROM authorized_client a WHERE a.registered_client_id = c.id)
                        RETURNING c.id
                        """)
                .param("cutoff", Instant.now().minus(GRACE_PERIOD).atOffset(ZoneOffset.UTC))
                .query(String.class)
                .list();
        if (removed.isEmpty()) {
            return 0;
        }
        for (var table : new String[]{"oauth2_authorization", "oauth2_authorization_consent"}) {
            jdbc.sql("DELETE FROM " + table + " WHERE registered_client_id IN (:ids)")
                    .param("ids", removed)
                    .update();
        }
        return removed.size();
    }
}
