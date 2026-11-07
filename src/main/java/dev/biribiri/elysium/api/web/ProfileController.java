package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.auth.TokenPrincipal;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
@Tag(name = "Profile", description = "Removing the user's profile images.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "profile/"))
public class ProfileController {

    private final ActorService actors;
    private final ProfileService profiles;
    private final AccountResponses accounts;

    ProfileController(ActorService actors, ProfileService profiles, AccountResponses accounts) {
        this.actors = actors;
        this.profiles = profiles;
        this.accounts = accounts;
    }

    @DeleteMapping("/avatar")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "deleteProfileAvatar",
            summary = "Remove the user's avatar",
            description = "Removing an avatar that isn't there succeeds too.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "profile/#delete-profile-avatar"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The user's account, as changed."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public CredentialAccountResponse deleteAvatar(@AuthenticationPrincipal TokenPrincipal token) {
        return change(token, ProfileChange.NONE.withAvatar(ProfileChange.Image.REMOVE));
    }

    @DeleteMapping("/header")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "deleteProfileHeader",
            summary = "Remove the user's header",
            description = "Removing a header that isn't there succeeds too.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "profile/#delete-profile-header"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The user's account, as changed."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public CredentialAccountResponse deleteHeader(@AuthenticationPrincipal TokenPrincipal token) {
        return change(token, ProfileChange.NONE.withHeader(ProfileChange.Image.REMOVE));
    }

    private CredentialAccountResponse change(TokenPrincipal token, ProfileChange change) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        if (!profiles.update(actorId, change)) {
            throw new RecordNotFoundException();
        }
        return actors.findLocalById(actorId).map(accounts::credential).orElseThrow(RecordNotFoundException::new);
    }
}
