package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Lets someone delete their own account. There's no API for this, so that an application can't do it with a token. Needing
 * the password means that a browser left logged in isn't enough either. Wrong passwords count toward the account's login
 * limits, since this could otherwise be used to guess one.
 */
@Controller
class DeleteAccountController {

    private final ActorService actors;
    private final AccountModeration moderation;
    private final AuthRateLimits limits;

    DeleteAccountController(ActorService actors, AccountModeration moderation, AuthRateLimits limits) {
        this.actors = actors;
        this.moderation = moderation;
        this.limits = limits;
    }

    @GetMapping(SecurityConfig.DELETE_ACCOUNT_PATH)
    String form() {
        return "delete-account";
    }

    @PostMapping(SecurityConfig.DELETE_ACCOUNT_PATH)
    String delete(
            @RequestParam(required = false) String password,
            Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
        var accountId = UUID.fromString(authentication.getName());
        var account = actors.findLocalById(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var wait = limits.beginPasswordCheck(request, account.username());
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(wait.get()));
            return failed(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.", model, response);
        }
        try {
            if (!moderation.deleteOwn(accountId, password)) {
                limits.loginFailed(request);
                return failed(HttpStatus.UNPROCESSABLE_CONTENT, "Incorrect password.", model, response);
            }
        } catch (AccountActionRefusedException e) {
            limits.loginNotCounted(request);
            return failed(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), model, response);
        } catch (PasswordHashingBusyException e) {
            limits.loginNotCounted(request);
            return failed(HttpStatus.SERVICE_UNAVAILABLE, "The server is busy. Try again in a moment.", model, response);
        } catch (RuntimeException e) {
            // Such as the database being down, which shouldn't use up anyone's attempts.
            limits.loginNotCounted(request);
            throw e;
        }
        limits.loginSucceeded(request);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return "redirect:" + SecurityConfig.LOGIN_PATH + "?deleted";
    }

    private static String failed(HttpStatus status, String error, Model model, HttpServletResponse response) {
        response.setStatus(status.value());
        model.addAttribute("error", error);
        return "delete-account";
    }
}
