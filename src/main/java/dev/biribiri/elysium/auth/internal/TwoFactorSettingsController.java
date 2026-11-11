package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turning two-factor login on and off, and replacing recovery codes. Like deleting an account, each of these needs the
 * password, so that a browser left logged in isn't enough, and wrong passwords count toward the account's login limits.
 *
 * <p>Pages that show the secret or recovery codes are answers to a form, never redirected to, so the secret is never in a URL,
 * and they're marked so that they aren't cached.
 */
@Controller
class TwoFactorSettingsController {

    private final ActorService actors;
    private final LocalAccounts accounts;
    private final TwoFactorAccounts twoFactor;
    private final AuthRateLimits limits;
    private final InstanceProperties instance;

    TwoFactorSettingsController(
            ActorService actors,
            LocalAccounts accounts,
            TwoFactorAccounts twoFactor,
            AuthRateLimits limits,
            InstanceProperties instance) {
        this.actors = actors;
        this.accounts = accounts;
        this.twoFactor = twoFactor;
        this.limits = limits;
        this.instance = instance;
    }

    @GetMapping(SecurityConfig.TWO_FACTOR_SETTINGS_PATH)
    String overview(
            @RequestParam(required = false) String disabled,
            Authentication authentication,
            Model model) {
        model.addAttribute("disabled", disabled != null);
        return overview(account(authentication), model);
    }

    @PostMapping(SecurityConfig.TWO_FACTOR_SETUP_PATH)
    String setUp(
            @RequestParam(required = false) String password,
            Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        var account = account(authentication);
        var refused = checkPassword(account, password, model, request, response);
        if (refused.isPresent()) {
            return refused.get();
        }
        var secret = TotpCodes.newSecret();
        if (!twoFactor.startSetup(account.id(), secret)) {
            return failed(HttpStatus.CONFLICT, "Two-factor login is already on.", account, model, response);
        }
        PendingSetup.start(request, account.id(), secret);
        return setupPage(account, secret, model, response);
    }

    @PostMapping(SecurityConfig.TWO_FACTOR_CONFIRM_PATH)
    String confirm(
            @RequestParam(required = false) String code,
            Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        var account = account(authentication);
        // Only for the session that typed the password, since this page shows the secret again after a wrong code, and
        // finishing setup gives out recovery codes.
        var secret = twoFactor.pendingSecret(account.id()).filter(found -> PendingSetup.isFor(request, account.id(), found));
        var generation = generationOf(request);
        if (secret.isEmpty() || generation.isEmpty()) {
            // Such as when it was turned on or set up again in another tab, or the form was sent twice.
            return "redirect:" + SecurityConfig.TWO_FACTOR_SETTINGS_PATH;
        }
        var wait = limits.beginCodeCheck(request, account.id());
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(wait.get()));
            model.addAttribute("error", "Too many attempts. Try again later.");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return setupPage(account, secret.get(), model, response);
        }
        try {
            var step = TotpCodes.matchingStep(secret.get(), code == null ? "" : code.strip(), Instant.now());
            if (step.isEmpty()) {
                limits.loginFailed(request);
                model.addAttribute("error", "That code didn't match. Check your phone's clock, and try the next code.");
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                return setupPage(account, secret.get(), model, response);
            }
            var confirmed = twoFactor.confirm(account.id(), secret.get(), step.getAsLong(), generation.get());
            limits.loginSucceeded(request);
            if (confirmed.isEmpty()) {
                // If the session's generation ended, ActiveAccountFilter logs it out there.
                return "redirect:" + SecurityConfig.TWO_FACTOR_SETTINGS_PATH;
            }
            startNewSession(authentication, confirmed.get().loginGeneration(), request, response);
            return recoveryCodesPage(confirmed.get().recoveryCodes(), true, model, response);
        } catch (RuntimeException e) {
            limits.loginNotCounted(request);
            throw e;
        }
    }

    @PostMapping(SecurityConfig.TWO_FACTOR_DISABLE_PATH)
    String disable(
            @RequestParam(required = false) String password,
            Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        var account = account(authentication);
        var refused = checkPassword(account, password, model, request, response);
        if (refused.isPresent()) {
            return refused.get();
        }
        twoFactor.disable(account.id());
        return "redirect:" + SecurityConfig.TWO_FACTOR_SETTINGS_PATH + "?disabled";
    }

    @PostMapping(SecurityConfig.RECOVERY_CODES_PATH)
    String replaceRecoveryCodes(
            @RequestParam(required = false) String password,
            Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        var account = account(authentication);
        var refused = checkPassword(account, password, model, request, response);
        if (refused.isPresent()) {
            return refused.get();
        }
        var codes = twoFactor.replaceRecoveryCodes(account.id());
        if (codes.isEmpty()) {
            return failed(HttpStatus.CONFLICT, "Two-factor login is off.", account, model, response);
        }
        return recoveryCodesPage(codes.get(), false, model, response);
    }

    private static Optional<Long> generationOf(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && LoginGenerationFilter.generationOf(session) instanceof Long generation
                ? Optional.of(generation)
                : Optional.empty();
    }

    private Actor account(Authentication authentication) {
        // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
        return actors.findLocalById(UUID.fromString(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * @return the page to show instead, if the password is wrong or can't be checked
     */
    private Optional<String> checkPassword(
            Actor account, String password, Model model, HttpServletRequest request, HttpServletResponse response) {
        var wait = limits.beginPasswordCheck(request, account.username());
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(wait.get()));
            return Optional.of(failed(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.", account, model, response));
        }
        try {
            if (!accounts.passwordMatches(account.id(), password)) {
                limits.loginFailed(request);
                return Optional.of(failed(HttpStatus.UNPROCESSABLE_CONTENT, "Incorrect password.", account, model, response));
            }
        } catch (PasswordHashingBusyException e) {
            limits.loginNotCounted(request);
            return Optional.of(failed(
                    HttpStatus.SERVICE_UNAVAILABLE, "The server is busy. Try again in a moment.", account, model, response));
        } catch (RuntimeException e) {
            // Such as the database being down, which shouldn't use up anyone's attempts.
            limits.loginNotCounted(request);
            throw e;
        }
        limits.loginSucceeded(request);
        return Optional.empty();
    }

    private String overview(Actor account, Model model) {
        var enabled = twoFactor.isEnabled(account.id());
        model.addAttribute("enabled", enabled);
        model.addAttribute("recoveryCodesLeft", enabled ? twoFactor.recoveryCodesLeft(account.id()) : 0);
        return "two-factor";
    }

    private String failed(HttpStatus status, String error, Actor account, Model model, HttpServletResponse response) {
        response.setStatus(status.value());
        model.addAttribute("error", error);
        return overview(account, model);
    }

    private String setupPage(Actor account, byte[] secret, Model model, HttpServletResponse response) {
        noStore(response);
        var uri = TotpCodes.otpAuthUri(secret, instance.baseUrl().getHost(), account.username() + "@" + instance.domain());
        model.addAttribute("qrCode", QrSvg.of(uri));
        // In groups, which is easier to type into an app than one long string.
        model.addAttribute("secret", String.join(" ", Base32.encode(secret).split("(?<=\\G.{4})")));
        return "two-factor-setup";
    }

    private static String recoveryCodesPage(
            List<String> codes, boolean justEnabled, Model model, HttpServletResponse response) {
        noStore(response);
        model.addAttribute("recoveryCodes", codes);
        model.addAttribute("justEnabled", justEnabled);
        return "recovery-codes";
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    /**
     * Notes in the session that it typed the password to start setting up this secret. Only a hash of the secret is noted,
     * since sessions are stored in the database. Turning two-factor login on ends the session, so this goes with it.
     */
    record PendingSetup(UUID actorId, String secretHash, Instant expiresAt) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private static final String SESSION_ATTRIBUTE = "elysium.twoFactorSetup";
        private static final Duration EXPIRY = Duration.ofMinutes(15);

        static void start(HttpServletRequest request, UUID actorId, byte[] secret) {
            request.getSession().setAttribute(
                    SESSION_ATTRIBUTE, new PendingSetup(actorId, hash(secret), Instant.now().plus(EXPIRY)));
        }

        static boolean isFor(HttpServletRequest request, UUID actorId, byte[] secret) {
            var session = request.getSession(false);
            return session != null
                    && session.getAttribute(SESSION_ATTRIBUTE) instanceof PendingSetup setup
                    && setup.actorId().equals(actorId)
                    && MessageDigest.isEqual(
                    setup.secretHash().getBytes(StandardCharsets.US_ASCII), hash(secret).getBytes(StandardCharsets.US_ASCII))
                    && Instant.now().isBefore(setup.expiresAt());
        }

        private static String hash(byte[] secret) {
            return SecretCodes.hash(Base32.encode(secret));
        }
    }

    /**
     * Moves this browser to a new session, once turning two-factor login on has ended all of the account's sessions, this one
     * included. The new one belongs to the account's new generation: see {@link LoginGenerationFilter}.
     */
    private static void startNewSession(
            Authentication authentication, long generation, HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        request.getSession(true);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        LoginGenerationFilter.loggingIn(request, generation);
        LoginGenerationFilter.loggedIn(request);
    }
}
