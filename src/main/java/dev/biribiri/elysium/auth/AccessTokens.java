package dev.biribiri.elysium.auth;

import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Looks up a grant's access token for work that runs after the request that made it, such as push notifications.
 */
@Service
public class AccessTokens {

    private final OAuth2AuthorizationService authorizations;

    AccessTokens(OAuth2AuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    /**
     * Revoking a token through {@code /oauth/revoke} only marks it invalid and keeps its grant, so a foreign key on the
     * grant can't tell other modules that it stopped working, and they have to ask here.
     *
     * @param authorizationId as in {@link TokenPrincipal#authorizationId()}
     * @return the token, or empty if it was revoked, has expired, or its grant was deleted
     */
    public Optional<String> activeToken(String authorizationId) {
        var authorization = authorizations.findById(authorizationId);
        if (authorization == null) {
            return Optional.empty();
        }
        var token = authorization.getToken(OAuth2AccessToken.class);
        if (token == null || !token.isActive()) {
            return Optional.empty();
        }
        return Optional.of(token.getToken().getTokenValue());
    }
}
