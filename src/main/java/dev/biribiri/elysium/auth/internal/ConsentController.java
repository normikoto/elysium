package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.ClientRegistrations;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Asks the user to approve an application. The form posts back to the authorization endpoint, and is protected by the
 * {@code state} value rather than a CSRF token. Spring only accepts that value from the user it was issued to.
 */
@Controller
class ConsentController {

    private final ClientRegistrations clients;
    private final ActorService actors;
    private final LocalAccounts accounts;

    ConsentController(ClientRegistrations clients, ActorService actors, LocalAccounts accounts) {
        this.clients = clients;
        this.actors = actors;
        this.accounts = accounts;
    }

    @GetMapping(SecurityConfig.CONSENT_PATH)
    String consent(
            @RequestParam("client_id") String clientId,
            @RequestParam String scope,
            @RequestParam String state,
            Principal principal,
            Model model) {
        var client = clients.findByClientId(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        var actorId = UUID.fromString(principal.getName());
        var username = actors.findLocalById(actorId)
                .map(Actor::username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));

        // This is only so that the page is accurate. SecurityConfig enforces it when the form is submitted.
        var requested = OAuthScopes.parse(scope);
        var grantable = accounts.isAdmin(actorId) ? requested : OAuthScopes.withoutAdmin(requested);

        model.addAttribute("clientName", client.name());
        model.addAttribute("clientId", clientId);
        model.addAttribute("state", state);
        model.addAttribute("username", username);
        model.addAttribute("scopes", List.copyOf(grantable));
        model.addAttribute("adminScopesLeftOut", grantable.size() < requested.size());
        model.addAttribute("authorizePath", SecurityConfig.AUTHORIZE_PATH);
        return "consent";
    }
}
