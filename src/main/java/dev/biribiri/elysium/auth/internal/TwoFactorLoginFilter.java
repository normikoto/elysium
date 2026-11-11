package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Finishes a login that's waiting for a code: see {@link TwoFactorChallenge}. It's a filter like Spring's own login form, so
 * that it saves the login, changes the session's ID, and replaces the CSRF token in the same way, and then goes on to the same
 * success handler.
 *
 * <p>Either a code from the authenticator app or a recovery code is accepted, told apart by their shapes. Wrong ones count
 * against {@link AuthRateLimits#beginCodeCheck}, and against the challenge's own attempts.
 *
 * <p>Not a bean, because Spring Boot would then also run it outside of the security filter chains.
 */
class TwoFactorLoginFilter extends AbstractAuthenticationProcessingFilter {

    static final String CODE_PARAMETER = "code";

    private final ActorService actors;
    private final LocalAccounts accounts;
    private final TwoFactorAccounts twoFactor;
    private final AuthRateLimits limits;

    private TwoFactorLoginFilter(
            ActorService actors, LocalAccounts accounts, TwoFactorAccounts twoFactor, AuthRateLimits limits) {
        super(pathPattern(HttpMethod.POST, SecurityConfig.TWO_FACTOR_LOGIN_PATH));
        this.actors = actors;
        this.accounts = accounts;
        this.twoFactor = twoFactor;
        this.limits = limits;
        setAuthenticationFailureHandler(this::failed);
    }

    /**
     * Adds the filter to a chain. This has to happen once the chain's other configurers have set up how sessions and logins are
     * saved, which is when a configurer's {@code configure} runs, the same as for Spring's login form.
     */
    static final class Configurer extends AbstractHttpConfigurer<Configurer, HttpSecurity> {

        private final ActorService actors;
        private final LocalAccounts accounts;
        private final TwoFactorAccounts twoFactor;
        private final AuthRateLimits limits;
        private final AuthenticationSuccessHandler success;

        Configurer(
                ActorService actors,
                LocalAccounts accounts,
                TwoFactorAccounts twoFactor,
                AuthRateLimits limits,
                AuthenticationSuccessHandler success) {
            this.actors = actors;
            this.accounts = accounts;
            this.twoFactor = twoFactor;
            this.limits = limits;
            this.success = success;
        }

        @Override
        public void configure(HttpSecurity http) {
            var filter = new TwoFactorLoginFilter(actors, accounts, twoFactor, limits);
            filter.setAuthenticationSuccessHandler(success);
            // Required, though the filter checks codes itself, since they aren't something a provider could check.
            filter.setAuthenticationManager(http.getSharedObject(AuthenticationManager.class));
            filter.setSessionAuthenticationStrategy(http.getSharedObject(SessionAuthenticationStrategy.class));
            filter.setSecurityContextRepository(http.getSharedObject(SecurityContextRepository.class));
            http.addFilterBefore(postProcess(filter), UsernamePasswordAuthenticationFilter.class);
        }
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
        var challenge = TwoFactorChallenge.current(request).orElseThrow(ChallengeEndedException::new);
        var actor = actors.findLocalById(challenge.actorId());
        // Ending the account's sessions, by replacing its password or suspending it, ends logins partway through too.
        if (actor.isEmpty() || !Objects.equals(
                accounts.loginGeneration(challenge.actorId()).orElse(null), challenge.loginGeneration())) {
            TwoFactorChallenge.end(request);
            throw new ChallengeEndedException();
        }
        var wait = limits.beginCodeCheck(request, challenge.actorId());
        if (wait.isPresent()) {
            throw new TooManyAttemptsException(wait.get());
        }
        try {
            if (!codeMatches(challenge, request.getParameter(CODE_PARAMETER))) {
                limits.loginFailed(request);
                throw challenge.failed(request) ? new BadCredentialsException("Wrong code") : new ChallengeEndedException();
            }
            TwoFactorChallenge.end(request);
            LoginGenerationFilter.loggingIn(request, challenge.loginGeneration());
            var user = accounts.loadUserByUsername(actor.get().username());
            var login = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
            // As Spring's ProviderManager does, so that the password's hash isn't kept in the session.
            login.eraseCredentials();
            return login;
        } catch (AuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            // Such as the database being down, which shouldn't use up anyone's attempts.
            limits.loginNotCounted(request);
            throw e;
        }
    }

    private boolean codeMatches(TwoFactorChallenge challenge, String typed) {
        if (typed == null) {
            return false;
        }
        var trimmed = typed.strip();
        var id = challenge.actorId();
        if (TotpCodes.looksLikeCode(trimmed)) {
            return twoFactor.enabledSecret(id)
                    .flatMap(secret -> TotpCodes.matchingStep(secret, trimmed, Instant.now()).stream().boxed().findFirst())
                    .filter(step -> twoFactor.useStep(id, step))
                    .isPresent();
        }
        return twoFactor.useRecoveryCode(id, trimmed);
    }

    private void failed(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        switch (failure) {
            case TooManyAttemptsException tooMany -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(tooMany.wait));
                response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
            }
            case ChallengeEndedException ended ->
                    new DefaultRedirectStrategy().sendRedirect(request, response, SecurityConfig.LOGIN_PATH + "?error");
            default -> new DefaultRedirectStrategy()
                    .sendRedirect(request, response, SecurityConfig.TWO_FACTOR_LOGIN_PATH + "?error");
        }
    }

    /**
     * There's no login waiting for a code, or it's run out of time or attempts, so the password has to be typed again.
     */
    private static final class ChallengeEndedException extends AuthenticationException {

        ChallengeEndedException() {
            super("No login is waiting for a code");
        }
    }

    private static final class TooManyAttemptsException extends AuthenticationException {

        private final transient Duration wait;

        TooManyAttemptsException(Duration wait) {
            super("Too many attempts");
            this.wait = wait;
        }
    }
}
