package dev.biribiri.elysium.auth.internal;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Issues the user token that {@code POST /api/v1/accounts} answers with, as Mastodon does, so that the app that signed
 * someone up doesn't have to send them through OAuth as well. It can be issued for a sign-up that has no account yet,
 * since the account takes the sign-up's ID. {@link TokenIntrospector} refuses it with Mastodon's messages until then.
 */
@Component
class SignUpTokens {

    /**
     * The same length and alphabet as the tokens Spring issues at {@code /oauth/token}.
     */
    private static final StringKeyGenerator TOKEN_VALUES =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    private final RegisteredClientRepository clients;
    private final OAuth2AuthorizationService authorizations;

    SignUpTokens(RegisteredClientRepository clients, OAuth2AuthorizationService authorizations) {
        this.clients = clients;
        this.authorizations = authorizations;
    }

    /**
     * @param applicationId the registered client that signed them up
     * @return the token, which has the client's scopes other than admin ones
     * @throws IllegalStateException if the client no longer exists
     */
    OAuth2AccessToken issue(String applicationId, UUID actorId) {
        var client = clients.findById(applicationId);
        if (client == null) {
            throw new IllegalStateException("The application was removed while it was signing someone up");
        }
        // A new account is never an admin, and changing its role later revokes grants with admin scopes anyway.
        var scopes = OAuthScopes.withoutAdmin(client.getScopes());
        // PostgreSQL stores microseconds, so this matches what introspection later reads back.
        var issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                TOKEN_VALUES.generateKey(),
                issuedAt,
                issuedAt.plus(client.getTokenSettings().getAccessTokenTimeToLive()),
                scopes);
        // TokenIntrospector only reads an authorization code grant as belonging to a user.
        authorizations.save(OAuth2Authorization.withRegisteredClient(client)
                .principalName(actorId.toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                .accessToken(token)
                .build());
        return token;
    }
}
