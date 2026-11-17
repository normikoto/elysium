package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.InvalidMigrationException;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

/**
 * Lets someone list the accounts that can move to theirs, which other servers check for before they move followers. There's
 * no API for this, as there isn't in Mastodon. An alias only lets another account say it's moving here, so it doesn't need
 * the password, unlike moving.
 */
@Controller
class AccountAliasesController {

    private static final String PAGE = "account-aliases";

    private final AccountMigrations migrations;
    private final MigrationTargets targets;

    AccountAliasesController(AccountMigrations migrations, MigrationTargets targets) {
        this.migrations = migrations;
        this.targets = targets;
    }

    @GetMapping(SecurityConfig.ALIASES_PATH)
    String list(Authentication authentication, Model model) {
        return show(accountId(authentication), model);
    }

    @PostMapping(SecurityConfig.ALIASES_PATH)
    String add(
            @RequestParam(required = false) String address,
            Authentication authentication,
            Model model,
            HttpServletResponse response) {
        var accountId = accountId(authentication);
        try {
            var alias = targets.find(accountId, address, false);
            if (alias.isEmpty()) {
                return failed(accountId, HttpStatus.UNPROCESSABLE_CONTENT, "That account couldn't be found.", model, response);
            }
            migrations.addAlias(accountId, alias.get());
        } catch (RemoteActorResolver.ResolveLimitException e) {
            return failed(accountId, HttpStatus.TOO_MANY_REQUESTS, "Too many lookups. Try again later.", model, response);
        } catch (InvalidMigrationException e) {
            return failed(accountId, HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage() + ".", model, response);
        }
        return "redirect:" + SecurityConfig.ALIASES_PATH;
    }

    @PostMapping(SecurityConfig.ALIASES_REMOVE_PATH)
    String remove(@RequestParam(required = false) String uri, Authentication authentication) {
        if (uri != null) {
            try {
                migrations.removeAlias(accountId(authentication), new URI(uri));
            } catch (URISyntaxException e) {
                // It can't be an alias, so there's nothing to remove.
            }
        }
        return "redirect:" + SecurityConfig.ALIASES_PATH;
    }

    private String failed(UUID accountId, HttpStatus status, String error, Model model, HttpServletResponse response) {
        response.setStatus(status.value());
        model.addAttribute("error", error);
        return show(accountId, model);
    }

    private String show(UUID accountId, Model model) {
        model.addAttribute("aliases", migrations.aliases(accountId).stream().map(URI::toString).toList());
        model.addAttribute("maxAliases", AccountMigrations.MAX_ALIASES);
        return PAGE;
    }

    /**
     * A logged in user's name is their actor ID: see {@code LocalAccounts.loadUserByUsername}.
     */
    private static UUID accountId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
