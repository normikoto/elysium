package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.auth.ClientRegistrations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The same configuration as {@code SignUpCleanupIntegrationTests}, so that they share a context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "db-scheduler.polling-interval=PT0.1S")
class UnauthorizedClientCleanupIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ClientRegistrations registrations;

    @Autowired
    private RegisteredClientRepository clients;

    @Autowired
    private OAuth2AuthorizationService authorizations;

    @Autowired
    private OAuth2AuthorizationConsentService consents;

    @Autowired
    private AuthorizedClients authorizedClients;

    @Test
    void removesOldClientsThatNobodyAuthorized() {
        var unused = register("unused");
        var started = register("started");
        authorizations.save(authorization(started, false));
        var recent = register("recent");
        var consented = register("consented");
        consents.save(OAuth2AuthorizationConsent.withId(consented, "someone").scope("read").build());
        var tokenIssued = register("token issued");
        authorizations.save(authorization(tokenIssued, true));
        var revoked = register("revoked");
        consents.save(OAuth2AuthorizationConsent.withId(revoked, "someone").scope("read").build());
        jdbc.sql("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = :id")
                .param("id", revoked)
                .update();
        for (var id : List.of(unused, started, consented, tokenIssued, revoked)) {
            age(id, AuthorizedClients.GRACE_PERIOD.plusMinutes(1));
        }
        age(recent, AuthorizedClients.GRACE_PERIOD.minusMinutes(1));

        // Waits for the job to appear, which happens when the scheduler starts.
        await().atMost(WAIT).until(() -> jdbc.sql("""
                UPDATE scheduled_tasks SET execution_time = now()
                WHERE task_name = :name AND picked = false
                """).param("name", UnauthorizedClientCleanupTask.NAME).update() == 1);

        await().atMost(WAIT).until(() -> clients.findById(unused) == null);
        assertThat(clients.findById(started)).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE registered_client_id = :id")
                .param("id", started)
                .query(Long.class)
                .single()).isZero();
        for (var id : List.of(recent, consented, tokenIssued, revoked)) {
            assertThat(clients.findById(id)).isNotNull();
        }
    }

    @Test
    void refusesToSaveAnythingForAClientThatsGone() {
        var removed = register("removed");
        var authorization = authorization(removed, true);
        jdbc.sql("DELETE FROM oauth2_registered_client WHERE id = :id").param("id", removed).update();

        assertThatThrownBy(() -> authorizations.save(authorization))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
        assertThatThrownBy(() -> consents.save(OAuth2AuthorizationConsent.withId(removed, "someone").scope("read").build()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE registered_client_id = :id")
                .param("id", removed)
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void removesMoreClientsThanFitInOneBatch() {
        jdbc.sql("""
                        INSERT INTO oauth2_registered_client (id, client_id, client_id_issued_at, client_name,
                            client_authentication_methods, authorization_grant_types, scopes, client_settings, token_settings)
                        SELECT 'batch-' || n, 'batch-' || n, now() - INTERVAL '2 days', 'batch', 'client_secret_basic',
                               'authorization_code', 'read', '{}', '{}'
                        FROM generate_series(1, 2500) n
                        """)
                .update();

        authorizedClients.removeUnauthorized();

        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_registered_client WHERE id LIKE 'batch-%'")
                .query(Long.class)
                .single()).isZero();
    }

    private String register(String name) {
        return registrations.register(new MockHttpServletRequest(), name, List.of("urn:ietf:wg:oauth:2.0:oob"), "read", null)
                .application()
                .id();
    }

    private OAuth2Authorization authorization(String clientId, boolean withToken) {
        var builder = OAuth2Authorization.withRegisteredClient(clients.findById(clientId))
                .id(UUID.randomUUID().toString())
                .principalName("someone")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
        if (withToken) {
            var now = Instant.now();
            builder.token(new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER, UUID.randomUUID().toString(), now, now.plusSeconds(60)));
        }
        return builder.build();
    }

    private void age(String clientId, Duration age) {
        jdbc.sql("UPDATE oauth2_registered_client SET client_id_issued_at = :issuedAt WHERE id = :id")
                .param("issuedAt", Timestamp.from(Instant.now().minus(age)))
                .param("id", clientId)
                .update();
    }
}
