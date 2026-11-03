package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Turns away login attempts over the limit before Spring Security hashes their passwords. This must not be a bean,
 * because Spring Boot would then also run it outside the security filter chain.
 */
class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN_SUBMISSION = pathPattern(HttpMethod.POST, SecurityConfig.LOGIN_PATH);

    private final AuthRateLimits limits;

    LoginRateLimitFilter(AuthRateLimits limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN_SUBMISSION.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var wait = limits.beginLogin(request);
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, AuthRateLimits.retryAfterSeconds(wait.get()));
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            // Spring Security's failure handler only sees authentication failures. This does nothing if the attempt
            // already finished.
            limits.loginNotCounted(request);
            throw e;
        }
    }
}
