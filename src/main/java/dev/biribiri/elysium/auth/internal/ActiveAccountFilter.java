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

/**
 * Ends the login session of an account that's since been suspended or deleted. Sessions are in the server's memory, which a
 * command can't reach, so this looks at the database on each request rather than expecting anything to end them. Only requests
 * with a session pay for this, and those are only for a few pages.
 *
 * <p>Not a bean, because Spring Boot would then also run it outside of the security filter chains.
 */
class ActiveAccountFilter extends OncePerRequestFilter {

    private final ActorService actors;

    ActiveAccountFilter(ActorService actors) {
        this.actors = actors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
        if (authentication instanceof UsernamePasswordAuthenticationToken login
                && Ids.parse(login.getName()).filter(id -> actors.findLocalById(id).isEmpty()).isPresent()) {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
