package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InvalidMigrationException;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorResolver;
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
 * Lets someone move their account to another, which has to list theirs as an alias first, and their followers are moved
 * there. There's no API for this, as there isn't in Mastodon. Followers can't be moved back, so it needs the password, and
 * wrong ones count toward the account's login limits, as they do when changing it. Stopping being marked as moved doesn't
 * move anyone, so it doesn't.
 */
@Controller
class MoveAccountController {

    private static final String PAGE = "move-account";

    private final ActorService actors;
    private final ProfileService profiles;
    private final AccountMigrations migrations;
    private final MigrationTargets targets;
    private final LocalAccounts accounts;
    private final AuthRateLimits limits;

    MoveAccountController(
            ActorService actors,
            ProfileService profiles,
            AccountMigrations migrations,
            MigrationTargets targets,
            LocalAccounts accounts,
            AuthRateLimits limits) {
        this.actors = actors;
        this.profiles = profiles;
        this.migrations = migrations;
        this.targets = targets;
        this.accounts = accounts;
        this.limits = limits;
    }

    @GetMapping(SecurityConfig.MOVE_PATH)
    String form(Authentication authentication, Model model) {
        return show(accountId(authentication), model);
    }

    @PostMapping(SecurityConfig.MOVE_PATH)
    String move(
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String password,
            Authentication authentication,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        var accountId = accountId(authentication);
        var account = actors.findLocalById(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var wait = limits.beginPasswordCheck(request, account.username());
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(wait.get()));
            return failed(accountId, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.", model, response);
        }
        try {
            if (!accounts.passwordMatches(accountId, password)) {
                limits.loginFailed(request);
                return failed(accountId, HttpStatus.UNPROCESSABLE_CONTENT, "Incorrect password.", model, response);
            }
        } catch (PasswordHashingBusyException e) {
            limits.loginNotCounted(request);
            return failed(accountId, HttpStatus.SERVICE_UNAVAILABLE, "The server is busy. Try again in a moment.", model,
                    response);
        } catch (RuntimeException e) {
            // Such as the database being down, which shouldn't use up anyone's attempts.
            limits.loginNotCounted(request);
            throw e;
        }
        limits.loginSucceeded(request);
        try {
            // Fetched again, since the account may have only just listed this one.
            var target = targets.findFresh(accountId, address, account.uri());
            if (target.isEmpty()) {
                return failed(accountId, HttpStatus.UNPROCESSABLE_CONTENT, "That account couldn't be found.", model, response);
            }
            migrations.moveLocal(accountId, target.get());
        } catch (RemoteActorResolver.ResolveLimitException e) {
            return failed(accountId, HttpStatus.TOO_MANY_REQUESTS, "Too many lookups. Try again later.", model, response);
        } catch (InvalidMigrationException e) {
            return failed(accountId, HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage() + ".", model, response);
        }
        return "redirect:" + SecurityConfig.MOVE_PATH;
    }

    @PostMapping(SecurityConfig.MOVE_CANCEL_PATH)
    String cancel(Authentication authentication) {
        migrations.undoLocalMove(accountId(authentication));
        return "redirect:" + SecurityConfig.MOVE_PATH;
    }

    private String failed(UUID accountId, HttpStatus status, String error, Model model, HttpServletResponse response) {
        response.setStatus(status.value());
        model.addAttribute("error", error);
        return show(accountId, model);
    }

    private String show(UUID accountId, Model model) {
        var movedTo = profiles.find(accountId).movedTo();
        model.addAttribute("movedTo", movedTo == null ? null : movedTo.toString());
        model.addAttribute("nextMoveAt", migrations.nextLocalMoveAt(accountId).map(Object::toString).orElse(null));
        model.addAttribute("cooldownDays", AccountMigrations.LOCAL_COOLDOWN.toDays());
        return PAGE;
    }

    /**
     * A logged in user's name is their actor ID: see {@code LocalAccounts.loadUserByUsername}.
     */
    private static UUID accountId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
