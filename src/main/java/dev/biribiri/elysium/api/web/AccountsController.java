package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountsController {

    private static final String PLACEHOLDER_IMAGE_PATH = "/images/missing.png";

    private final ActorService actors;
    private final InstanceProperties instance;

    AccountsController(ActorService actors, InstanceProperties instance) {
        this.actors = actors;
        this.instance = instance;
    }

    @GetMapping("/verify_credentials")
    @PreAuthorize("hasAnyAuthority('SCOPE_profile', 'SCOPE_read:accounts')")
    public ResponseEntity<?> verifyCredentials(@AuthenticationPrincipal TokenPrincipal token) {
        if (token.actorId().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(Map.of("error", "This method requires an authenticated user"));
        }
        var placeholder = instance.resolve(PLACEHOLDER_IMAGE_PATH).toString();
        return actors.findLocalById(token.actorId().get())
                .<ResponseEntity<?>>map(actor -> ResponseEntity.ok(CredentialAccountResponse.from(actor, placeholder)))
                // The token outlived its account, so it no longer identifies anyone.
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "The access token is invalid")));
    }
}
