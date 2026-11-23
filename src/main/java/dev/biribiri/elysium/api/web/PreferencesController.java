package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.PostingDefaults;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Preferences", description = "The user's posting and reading defaults.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "preferences/"))
public class PreferencesController {

    private final ActorService actors;
    private final PostingDefaults postingDefaults;

    PreferencesController(ActorService actors, PostingDefaults postingDefaults) {
        this.actors = actors;
        this.postingDefaults = postingDefaults;
    }

    @GetMapping("/api/v1/preferences")
    @PreAuthorize("hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getPreferences",
            summary = "View the user's preferences",
            description = """
                    Differences from Mastodon: only `posting:default:quote_policy` can be changed, through \
                    `source[quote_policy]` in `update_credentials`. The other preferences have the values a new \
                    account would have on Mastodon, and `posting:default:language` is always null.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "preferences/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The user's preferences."),
                    @ApiResponse(responseCode = "422", description = "The token does not identify a user.")
            })
    public ResponseEntity<PreferencesResponse> get(@AuthenticationPrincipal TokenPrincipal token) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        return ResponseEntity.of(actors.findLocalById(actorId)
                .map(actor -> PreferencesResponse.from(postingDefaults.quotePolicy(actor.id()))));
    }
}
