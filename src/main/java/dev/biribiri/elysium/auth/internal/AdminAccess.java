package dev.biribiri.elysium.auth.internal;

import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Allows only admins, going by their current role. {@code hasRole("ADMIN")} would use the authorities a login session was
 * created with, so someone whose role was removed would keep access until the server restarted.
 */
@Component
class AdminAccess implements AuthorizationManager<RequestAuthorizationContext> {

    private final LocalAccounts accounts;
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    AdminAccess(LocalAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(isAdmin(authentication.get()));
    }

    /**
     * Whether a browser login, or an anonymous visitor, is currently an admin.
     */
    boolean isAdmin(Authentication authentication) {
        // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
        return trustResolver.isAuthenticated(authentication)
                && accounts.isAdmin(UUID.fromString(authentication.getName()));
    }
}
