package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Lets someone change their own password. There's no API for this, as there isn't in Mastodon, so that an application can't do
 * it with a token. Needing the current password means that a browser left logged in isn't enough, and wrong ones count toward
 * the account's login limits, since this could otherwise be used to guess it. Someone changing their password may be doing so
 * because it leaked, so every other browser is logged out and every application loses its access.
 */
@Controller
class ChangePasswordController {

    private static final String PAGE = "change-password";

    private final ActorService actors;
    private final LocalAccounts accounts;
    private final AuthRateLimits limits;

    ChangePasswordController(ActorService actors, LocalAccounts accounts, AuthRateLimits limits) {
        this.actors = actors;
        this.accounts = accounts;
        this.limits = limits;
    }

    @GetMapping(SecurityConfig.CHANGE_PASSWORD_PATH)
    String form(@RequestParam(required = false) String changed, Model model) {
        model.addAttribute("changed", changed != null);
        return show(model);
    }

    @PostMapping(SecurityConfig.CHANGE_PASSWORD_PATH)
    String change(
            @RequestParam(required = false) String currentPassword,
            @RequestParam(required = false) String newPassword,
            @RequestParam(required = false) String confirmation,
            Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
        var accountId = UUID.fromString(authentication.getName());
        var account = actors.findLocalById(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Before the current password is checked, so that a typo in the new one doesn't use up an attempt.
        if (!LocalAccounts.isValidPassword(newPassword)) {
            return failed(HttpStatus.UNPROCESSABLE_CONTENT, "Passwords must be %d to %d characters."
                    .formatted(LocalAccounts.MIN_PASSWORD_LENGTH, LocalAccounts.MAX_PASSWORD_LENGTH), model, response);
        }
        if (!newPassword.equals(confirmation)) {
            return failed(HttpStatus.UNPROCESSABLE_CONTENT, "The new passwords don't match.", model, response);
        }

        var wait = limits.beginPasswordCheck(request, account.username());
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(wait.get()));
            return failed(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.", model, response);
        }
        try {
            if (!accounts.passwordMatches(accountId, currentPassword)) {
                limits.loginFailed(request);
                return failed(HttpStatus.UNPROCESSABLE_CONTENT, "Incorrect password.", model, response);
            }
        } catch (PasswordHashingBusyException e) {
            limits.loginNotCounted(request);
            return busy(model, response);
        } catch (RuntimeException e) {
            // Such as the database being down, which shouldn't use up anyone's attempts.
            limits.loginNotCounted(request);
            throw e;
        }
        limits.loginSucceeded(request);

        var generation = LoginGenerationFilter.generationOf(request);
        if (generation.isEmpty()) {
            return "redirect:" + SecurityConfig.SETTINGS_PATH;
        }
        try {
            var newGeneration = accounts.changePassword(accountId, newPassword, generation.get());
            if (newGeneration.isEmpty()) {
                // The session's generation ended, so ActiveAccountFilter logs it out there.
                return "redirect:" + SecurityConfig.SETTINGS_PATH;
            }
            LoginGenerationFilter.startNewSession(authentication, newGeneration.get(), request, response);
        } catch (PasswordHashingBusyException e) {
            return busy(model, response);
        }
        return "redirect:" + SecurityConfig.CHANGE_PASSWORD_PATH + "?changed";
    }

    private static String busy(Model model, HttpServletResponse response) {
        return failed(HttpStatus.SERVICE_UNAVAILABLE, "The server is busy. Try again in a moment.", model, response);
    }

    private static String failed(HttpStatus status, String error, Model model, HttpServletResponse response) {
        response.setStatus(status.value());
        model.addAttribute("error", error);
        model.addAttribute("changed", false);
        return show(model);
    }

    private static String show(Model model) {
        model.addAttribute("minLength", LocalAccounts.MIN_PASSWORD_LENGTH);
        model.addAttribute("maxLength", LocalAccounts.MAX_PASSWORD_LENGTH);
        return PAGE;
    }
}
