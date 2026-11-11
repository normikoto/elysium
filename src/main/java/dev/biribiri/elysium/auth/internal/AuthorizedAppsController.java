package dev.biribiri.elysium.auth.internal;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Lets someone see which applications have access to their account and cut them off. Unlike two-factor login, this doesn't
 * ask for the password, so that someone whose token leaked can act quickly. It can only take access away.
 */
@Controller
class AuthorizedAppsController {

    private final AuthorizedApps apps;

    AuthorizedAppsController(AuthorizedApps apps) {
        this.apps = apps;
    }

    @GetMapping(SecurityConfig.AUTHORIZED_APPS_PATH)
    String list(@RequestParam(required = false) String revoked, Authentication authentication, Model model) {
        model.addAttribute("apps", apps.list(actorId(authentication)));
        model.addAttribute("revoked", revoked != null);
        return "authorized-apps";
    }

    @PostMapping(SecurityConfig.REVOKE_AUTHORIZED_APP_PATH)
    String revoke(@PathVariable String id, Authentication authentication) {
        apps.revoke(actorId(authentication), id);
        return "redirect:" + SecurityConfig.AUTHORIZED_APPS_PATH + "?revoked";
    }

    private static UUID actorId(Authentication authentication) {
        // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
        return UUID.fromString(authentication.getName());
    }
}
