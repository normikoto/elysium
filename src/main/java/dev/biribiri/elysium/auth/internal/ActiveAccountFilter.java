package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Ends a login session that outlived what it depends on: an account that's since been suspended or deleted, or a password that's
 * since been replaced. Those all delete the account's sessions, but a login that's still in progress at that moment saves its
 * session afterward, so this looks at the database on each request. Only requests with a session pay for this, and those are
 * only for a few pages.
 *
 * <p>Not a bean, because Spring Boot would then also run it outside of the security filter chains.
 */
class ActiveAccountFilter extends OncePerRequestFilter {

    private final ActorService actors;
    private final LocalAccounts accounts;

    ActiveAccountFilter(ActorService actors, LocalAccounts accounts) {
        this.actors = actors;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
        if (authentication instanceof UsernamePasswordAuthenticationToken login
                && Ids.parse(login.getName()).filter(id -> hasEnded(id, request)).isPresent()) {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }

    private boolean hasEnded(UUID actorId, HttpServletRequest request) {
        if (actors.findLocalById(actorId).isEmpty()) {
            return true;
        }
        var session = request.getSession(false);
        return session != null && !Objects.equals(
                LoginGenerationFilter.generationOf(session), accounts.passwordGeneration(actorId).orElse(null));
    }
}
