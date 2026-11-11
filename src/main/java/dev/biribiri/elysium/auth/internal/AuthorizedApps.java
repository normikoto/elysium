package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.auth.ClientRegistrations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The applications a user has given access to their account. App tokens record the client as their principal, so nothing here
 * can reach them.
 */
@Component
class AuthorizedApps {

    private final JdbcClient jdbc;
    private final OAuth2AuthorizationService authorizations;
    private final OAuth2AuthorizationConsentService consents;
    private final ClientRegistrations clients;
    private final TransactionTemplate transaction;

    AuthorizedApps(
            JdbcClient jdbc,
            OAuth2AuthorizationService authorizations,
            OAuth2AuthorizationConsentService consents,
            ClientRegistrations clients,
            TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.authorizations = authorizations;
        this.consents = consents;
        this.clients = clients;
        this.transaction = transaction;
    }

    /**
     * Lists every application with a stored consent or an active token. Either is enough on its own: a consent lets the application
     * get a token without asking, and a token keeps working after a later consent request is denied. Opening the consent page
     * also stores an authorization, which has neither, and doesn't count.
     */
    List<AuthorizedApp> list(UUID actorId) {
        var principal = actorId.toString();
        var grants = new HashMap<String, Grant>();
        addConsents(principal, grants);
        addTokens(principal, grants);
        return grants.entrySet().stream()
                .filter(entry -> entry.getValue().active)
                .flatMap(entry -> clients.findById(entry.getKey())
                        .map(client -> new AuthorizedApp(
                                client.id(),
                                client.name(),
                                client.website(),
                                entry.getValue().scopes,
                                entry.getValue().authorizedAt))
                        .stream())
                .sorted(Comparator.comparing(AuthorizedApp::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void addConsents(String principal, HashMap<String, Grant> grants) {
        var clientIds = jdbc.sql("""
                        SELECT registered_client_id FROM oauth2_authorization_consent WHERE principal_name = :principalName
                        """)
                .param("principalName", principal)
                .query(String.class)
                .list();
        for (var clientId : clientIds) {
            var consent = consents.findById(clientId, principal);
            if (consent != null) {
                var grant = grants.computeIfAbsent(clientId, id -> new Grant());
                grant.active = true;
                grant.scopes.addAll(consent.getScopes());
            }
        }
    }

    /**
     * Loads each authorization rather than filtering in SQL, so that "active" means what it does to {@link TokenIntrospector},
     * and doesn't depend on how Spring serializes a token's metadata.
     */
    private void addTokens(String principal, HashMap<String, Grant> grants) {
        var ids = jdbc.sql("""
                        SELECT id FROM oauth2_authorization
                        WHERE principal_name = :principalName AND access_token_value IS NOT NULL
                        """)
                .param("principalName", principal)
                .query(String.class)
                .list();
        for (var id : ids) {
            var authorization = authorizations.findById(id);
            if (authorization == null) {
                continue;
            }
            var token = authorization.getToken(OAuth2AccessToken.class);
            var grant = grants.computeIfAbsent(authorization.getRegisteredClientId(), client -> new Grant());
            // Consents have no date, so even a token that's been revoked says when an application was approved.
            grant.noteIssued(token.getToken().getIssuedAt());
            if (token.isActive()) {
                grant.active = true;
                grant.scopes.addAll(token.getToken().getScopes());
            }
        }
    }

    /**
     * Removes this user's tokens for an application, any codes it hasn't exchanged yet, and the consent that would let it get new
     * ones without asking. Doing nothing for an unknown application makes a form sent twice harmless.
     */
    void revoke(UUID actorId, String registeredClientId) {
        transaction.executeWithoutResult(status -> {
            for (var table : List.of("oauth2_authorization", "oauth2_authorization_consent")) {
                jdbc.sql("DELETE FROM " + table
                                + " WHERE registered_client_id = :registeredClientId AND principal_name = :principalName")
                        .param("registeredClientId", registeredClientId)
                        .param("principalName", actorId.toString())
                        .update();
            }
        });
    }

    /**
     * @param website      the application's homepage, or null
     * @param authorizedAt when its newest token was issued, or null if that's not known
     */
    record AuthorizedApp(String id, String name, String website, Set<String> scopes, Instant authorizedAt) {
    }

    private static final class Grant {

        private final Set<String> scopes = new TreeSet<>();
        private boolean active;
        private Instant authorizedAt;

        private void noteIssued(Instant issuedAt) {
            if (issuedAt != null && (authorizedAt == null || issuedAt.isAfter(authorizedAt))) {
                authorizedAt = issuedAt;
            }
        }
    }
}
