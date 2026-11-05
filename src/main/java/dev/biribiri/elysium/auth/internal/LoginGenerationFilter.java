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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Notes which generation of an account's sessions a login belongs to, so that ending its sessions, by replacing the password or
 * suspending the account, ends this one too. That deletes the account's sessions, but a login that's already checking the password
 * only saves its session afterward, and successful logins have no limit, so someone with a stolen password could keep trying until
 * one of them lands there.
 *
 * <p>The account's generation is read before the password is checked. If that's the generation from before the sessions ended, it
 * won't match on the next request, and {@link ActiveAccountFilter} ends the session. If it's the one from after, then the check also
 * reads the new password and whether the account is suspended. This doesn't compare times, because a transaction's time is from
 * before it commits, which would leave a gap.
 *
 * <p>This also notes the session's ID after logging in. Spring Session writes back the ID that each request read a session with, so
 * a request that overlaps a login can put back the ID from before it, with the login still in the session. Whoever planted that ID
 * could send requests until one overlaps, so {@link ActiveAccountFilter} ends a session whose ID isn't the one noted here. Only a
 * hash is noted, since what's stored shouldn't be enough to take over a session.
 *
 * <p>Not a bean, because Spring Boot would then also run it outside of the security filter chains.
 */
class LoginGenerationFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN_SUBMISSION = pathPattern(HttpMethod.POST, SecurityConfig.LOGIN_PATH);
    private static final String REQUEST_ATTRIBUTE = LoginGenerationFilter.class.getName() + ".generation";
    private static final String SESSION_ATTRIBUTE = "elysium.loginGeneration";
    private static final String LOGIN_ID_ATTRIBUTE = "elysium.loginSessionIdHash";

    private final LocalAccounts accounts;

    LoginGenerationFilter(LocalAccounts accounts) {
        this.accounts = accounts;
    }

    /**
     * Records the generation that a successful login belongs to in its session, and its ID, which has changed by now.
     */
    static void loggedIn(HttpServletRequest request) {
        var session = request.getSession();
        session.setAttribute(SESSION_ATTRIBUTE, request.getAttribute(REQUEST_ATTRIBUTE));
        session.setAttribute(LOGIN_ID_ATTRIBUTE, hash(session.getId()));
    }

    /**
     * @return the generation a session's login belongs to, if it has one
     */
    static Object generationOf(HttpSession session) {
        return session.getAttribute(SESSION_ATTRIBUTE);
    }

    /**
     * @return whether a session still has the ID it got when logging in
     */
    static boolean hasLoginId(HttpSession session) {
        return hash(session.getId()).equals(session.getAttribute(LOGIN_ID_ATTRIBUTE));
    }

    private static String hash(String id) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
            accounts.loginGeneration(username.trim())
                    .ifPresent(generation -> request.setAttribute(REQUEST_ATTRIBUTE, generation));
        }
        chain.doFilter(request, response);
    }
}
