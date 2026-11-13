package dev.biribiri.elysium.auth.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Keeps track of which clients have ever been authorized, so that {@link UnauthorizedClientCleanupTask} can remove the
 * ones that never were.
 *
 * <p>Spring's tables have no foreign key to the client, so anything saved for one has to go through {@link #lock} first.
 * Otherwise a save that commits while a cleanup is removing the client would leave rows behind that point at nothing,
 * and Spring fails to read those.
 */
@Component
class AuthorizedClients {

    /**
     * How long someone has after registering an app to log in with it.
     */
    static final Duration GRACE_PERIOD = Duration.ofDays(1);

    /**
     * Keeps each transaction short, and well under the number of rows a run might find after a flood of registrations.
     */
    private static final int BATCH_SIZE = 1000;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    AuthorizedClients(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /**
     * Keeps the client from being removed until the transaction ends. A cleanup that's already removing it makes this
     * wait, and then fail.
     *
     * @throws OAuth2AuthenticationException with {@code invalid_client} if the client is gone, which Spring turns into
     *                                       the OAuth error response
     */
    void lock(String registeredClientId) {
        var found = jdbc.sql("SELECT id FROM oauth2_registered_client WHERE id = :id FOR KEY SHARE")
                .param("id", registeredClientId)
                .query(String.class)
                .optional();
        if (found.isEmpty()) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
    }

    /**
     * Records that a client was authorized, after {@link #lock} in the same transaction.
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
    int removeUnauthorized() {
        var cutoff = Instant.now().minus(GRACE_PERIOD).atOffset(ZoneOffset.UTC);
        var total = 0;
        int removed;
        do {
            removed = transaction.execute(status -> removeBatch(cutoff));
            total += removed;
        } while (removed == BATCH_SIZE);
        return total;
    }

    private int removeBatch(OffsetDateTime cutoff) {
        var ids = jdbc.sql("""
                        DELETE FROM oauth2_registered_client
                        WHERE id IN (SELECT c.id
                                     FROM oauth2_registered_client c
                                     WHERE c.client_id_issued_at <= :cutoff
                                       AND NOT EXISTS (SELECT 1 FROM authorized_client a WHERE a.registered_client_id = c.id)
                                     LIMIT :batchSize)
                        RETURNING id
                        """)
                .param("cutoff", cutoff)
                .param("batchSize", BATCH_SIZE)
                .query(String.class)
                .list()
                .toArray(String[]::new);
        if (ids.length == 0) {
            return 0;
        }
        // Separate statements, so that they see what a save that held its lock on the client committed while this waited.
        // The IDs go as one array, since a list would need a parameter each.
        jdbc.sql("DELETE FROM oauth2_authorization WHERE registered_client_id = ANY (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ANY (:ids)")
                .param("ids", ids)
                .update();
        return ids.length;
    }
}
