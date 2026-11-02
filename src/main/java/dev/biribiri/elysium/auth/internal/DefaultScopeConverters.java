package dev.biribiri.elysium.auth.internal;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ClientCredentialsAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.Set;

/**
 * Applies Mastodon's default scope. Spring would otherwise issue a token with no scopes when a request omits them.
 */
final class DefaultScopeConverters {

    private static final Set<String> DEFAULT_SCOPES = Set.of(OAuthScopes.DEFAULT_SCOPE);

    private DefaultScopeConverters() {
    }

    static AuthenticationConverter authorizationRequest() {
        var delegate = new OAuth2AuthorizationCodeRequestAuthenticationConverter();
        return request -> {
            var authentication = delegate.convert(request);
            if (authentication instanceof OAuth2AuthorizationCodeRequestAuthenticationToken token
                    && token.getScopes().isEmpty()) {
                return new OAuth2AuthorizationCodeRequestAuthenticationToken(
                        token.getAuthorizationUri(),
                        token.getClientId(),
                        (Authentication) token.getPrincipal(),
                        token.getRedirectUri(),
                        token.getState(),
                        DEFAULT_SCOPES,
                        token.getAdditionalParameters());
            }
            return authentication;
        };
    }

    static AuthenticationConverter clientCredentialsRequest() {
        var delegate = new OAuth2ClientCredentialsAuthenticationConverter();
        return request -> {
            var authentication = delegate.convert(request);
            if (authentication instanceof OAuth2ClientCredentialsAuthenticationToken token
                    && token.getScopes().isEmpty()) {
                return new OAuth2ClientCredentialsAuthenticationToken(
                        (Authentication) token.getPrincipal(),
                        DEFAULT_SCOPES,
                        token.getAdditionalParameters());
            }
            return authentication;
        };
    }
}
