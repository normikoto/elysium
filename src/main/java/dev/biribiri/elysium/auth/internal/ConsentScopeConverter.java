package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationConsentAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Stops regular users from approving admin scopes, whatever the consent form they submitted contains. If that leaves no
 * scopes, then it's a denial. This has to happen here because it's where Spring gets the scopes it issues. Removing them
 * from the authorities in a consent customizer only changes the consent it stores.
 */
class ConsentScopeConverter implements AuthenticationConverter {

    private final OAuth2AuthorizationConsentAuthenticationConverter delegate =
            new OAuth2AuthorizationConsentAuthenticationConverter();
    private final AdminAccess adminAccess;

    ConsentScopeConverter(AdminAccess adminAccess) {
        this.adminAccess = adminAccess;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        var authentication = delegate.convert(request);
        if (authentication instanceof OAuth2AuthorizationConsentAuthenticationToken token
                && token.getScopes().stream().anyMatch(OAuthScopes::isAdmin)
                && !adminAccess.isAdmin((Authentication) token.getPrincipal())) {
            return new OAuth2AuthorizationConsentAuthenticationToken(
                    token.getAuthorizationUri(),
                    token.getClientId(),
                    (Authentication) token.getPrincipal(),
                    token.getState(),
                    OAuthScopes.withoutAdmin(token.getScopes()),
                    token.getAdditionalParameters());
        }
        return authentication;
    }
}
