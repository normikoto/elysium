package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.text.Hashtags;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "Tags", description = "Viewing hashtags.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "tags/"))
public class TagsController {

    private final TagResponses tags;

    TagsController(TagResponses tags) {
        this.tags = tags;
    }

    /**
     * Mastodon needs no scope for this, so any token works, as does none.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAnonymous() or isAuthenticated()")
    @Operation(
            operationId = "getTag",
            summary = "View a hashtag",
            description = """
                    Differences from Mastodon: `history` only counts public statuses stored on this server. Tags can't \
                    be followed or featured, so `following` is always false, and `featuring` is left out.

                    Any valid tag name is found, even one that nothing uses, which has a `history` of zeros. Any token \
                    works, with any scope, as does none.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "tags/#get"),
            parameters = @Parameter(name = "id", description = "The tag's name, without its `#`."),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The tag."),
                    @ApiResponse(responseCode = "404", description = "The name isn't a valid tag name.")
            })
    public TagResponse tag(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var name = Hashtags.parse(id).orElseThrow(RecordNotFoundException::new);
        return tags.build(List.of(name), RequestParameters.viewer(token).isPresent()).getFirst();
    }
}
