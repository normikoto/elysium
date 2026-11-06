package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
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
 * Lets someone change their own account's settings. Applications can change the same ones through the API.
 */
@Controller
class SettingsController {

    private final ActorService actors;

    SettingsController(ActorService actors) {
        this.actors = actors;
    }

    @GetMapping(SecurityConfig.SETTINGS_PATH)
    String form(@RequestParam(required = false) String saved, Authentication authentication, Model model) {
        var accountId = accountId(authentication);
        model.addAttribute("hideCollections", actors.hidesCollections(accountId));
        model.addAttribute("saved", saved != null);
        return "settings";
    }

    /**
     * A checkbox that isn't ticked isn't sent, so a missing value means off.
     */
    @PostMapping(SecurityConfig.SETTINGS_PATH)
    String save(@RequestParam(required = false) String hideCollections, Authentication authentication) {
        if (!actors.setHidesCollections(accountId(authentication), "true".equals(hideCollections))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:" + SecurityConfig.SETTINGS_PATH + "?saved";
    }

    /**
     * A logged in user's name is their actor ID: see {@link LocalAccounts#loadUserByUsername}. The account is active, or
     * {@link ActiveAccountFilter} would have logged them out.
     */
    private UUID accountId(Authentication authentication) {
        var id = UUID.fromString(authentication.getName());
        if (actors.findLocalById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return id;
    }
}
