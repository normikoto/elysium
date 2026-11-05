package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Notes which password a login is being checked against, so that replacing the password ends its session too. Replacing a password
 * deletes the account's sessions, but a login that's already checking the old password only saves its session afterward, and
 * successful logins have no limit, so someone with a stolen password could keep trying until one of them lands there.
 *
 * <p>The account's generation is read before the password is checked. If that's the generation from before the password was
 * replaced, it won't match on the next request, and {@link ActiveAccountFilter} ends the session. If it's the one from after, then
 * the check also reads the new password. This doesn't compare times, because a transaction's time is from before it commits, which
 * would leave a gap.
 *
 * <p>Not a bean, because Spring Boot would then also run it outside of the security filter chains.
 */
class LoginGenerationFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN_SUBMISSION = pathPattern(HttpMethod.POST, SecurityConfig.LOGIN_PATH);
    private static final String REQUEST_ATTRIBUTE = LoginGenerationFilter.class.getName() + ".generation";
    private static final String SESSION_ATTRIBUTE = "elysium.passwordGeneration";

    private final LocalAccounts accounts;

    LoginGenerationFilter(LocalAccounts accounts) {
        this.accounts = accounts;
    }

    /**
     * Records the generation that a successful login was checked against in its session.
     */
    static void loggedIn(HttpServletRequest request) {
        request.getSession().setAttribute(SESSION_ATTRIBUTE, request.getAttribute(REQUEST_ATTRIBUTE));
    }

    /**
     * @return the generation a session's login was checked against, if it has one
     */
    static Object generationOf(HttpSession session) {
        return session.getAttribute(SESSION_ATTRIBUTE);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN_SUBMISSION.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var username = request.getParameter("username");
        if (username != null) {
            // Spring Security trims it in the same way when logging in.
            accounts.passwordGeneration(username.trim())
                    .ifPresent(generation -> request.setAttribute(REQUEST_ATTRIBUTE, generation));
        }
        chain.doFilter(request, response);
    }
}
