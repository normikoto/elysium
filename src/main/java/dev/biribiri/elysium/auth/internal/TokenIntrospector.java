package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.TokenPrincipal;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Checks bearer tokens directly against the authorization database.
 */
@Component
class TokenIntrospector implements OpaqueTokenIntrospector {

    private final OAuth2AuthorizationService authorizations;
    private final ActorService actors;
    private final LocalAccounts accounts;

    TokenIntrospector(OAuth2AuthorizationService authorizations, ActorService actors, LocalAccounts accounts) {
        this.authorizations = authorizations;
        this.actors = actors;
        this.accounts = accounts;
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        var authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null) {
            throw new BadOpaqueTokenException("Unknown access token");
        }
        var accessToken = authorization.getToken(OAuth2AccessToken.class);
        if (accessToken == null || !accessToken.isActive()) {
            throw new BadOpaqueTokenException("Inactive access token");
        }

        var actorId = actorId(authorization);
        return new TokenPrincipal(
                authorization.getRegisteredClientId(),
                actorId,
                effectiveScopes(accessToken.getToken().getScopes(), actorId),
                accessToken.getToken().getIssuedAt(),
                accessToken.getToken().getExpiresAt());
    }

    /**
     * Admin scopes only work while the token's user is an admin, which is checked here so that removing the role applies
     * to tokens straight away. App tokens have no user, and anyone can register an app with any scopes and get one, so
     * they never have admin access.
     */
    private Set<String> effectiveScopes(Set<String> granted, Optional<UUID> actorId) {
        var scopes = OAuthScopes.expand(granted);
        // Most tokens have no admin scopes, and they shouldn't need another query on every request.
        if (scopes.stream().noneMatch(OAuthScopes::isAdmin) || actorId.filter(accounts::isAdmin).isPresent()) {
            return scopes;
        }
        return OAuthScopes.withoutAdmin(scopes);
    }

    /**
     * App tokens record the client as their principal, so that name can only be read as an actor on a user token. Tokens
     * have no foreign key to their actor, so a user token can outlive its account, and checking that here means no
     * endpoint has to.
     */
    private Optional<UUID> actorId(OAuth2Authorization authorization) {
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(authorization.getAuthorizationGrantType())) {
            return Optional.empty();
        }
        var actorId = UUID.fromString(authorization.getPrincipalName());
        if (actors.findLocalById(actorId).isEmpty()) {
            throw new BadOpaqueTokenException("The account for this access token no longer exists");
        }
        return Optional.of(actorId);
    }
}
