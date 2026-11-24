package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.TagFollows;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "Tags", description = "Viewing and following hashtags.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "tags/"))
public class TagsController {

    private static final String NAME = "The tag's name, without its `#`.";

    private final TagResponses tags;
    private final TagFollows follows;

    TagsController(TagResponses tags, TagFollows follows) {
        this.tags = tags;
        this.follows = follows;
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
                    Differences from Mastodon: `history` only counts public statuses stored on this server. Tags \
                    can't be featured, so `featuring` is omitted.

                    Any valid tag name is found, even one that nothing uses, which has a `history` of zeros. Any \
                    token works, with any scope, as does none. `following` is omitted without a user token.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "tags/#get"),
            parameters = @Parameter(name = "id", description = NAME),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The tag."),
                    @ApiResponse(responseCode = "404", description = "The name isn't a valid tag name.")
            })
    public TagResponse tag(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var name = Hashtags.parse(id).orElseThrow(RecordNotFoundException::new);
        return tags.build(List.of(name), RequestParameters.viewer(token)).getFirst();
    }

    @PostMapping("/{id}/follow")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "followTag",
            summary = "Follow a hashtag",
            description = """
                    Adds public statuses with the tag to the user's home timeline, replies included, from accounts \
                    the user doesn't follow too, except accounts they've blocked or muted. Statuses already stored here \
                    are included straight away. Any valid tag name can be followed, even one that nothing uses. \
                    Following a tag again has no additional effect. Other servers aren't told.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "tags/#follow"),
            parameters = @Parameter(name = "id", description = NAME),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The tag, now followed."),
                    @ApiResponse(responseCode = "404", description = "The name isn't a valid tag name."),
                    @ApiResponse(responseCode = "422", description = "The token does not identify a user.")
            })
    public TagResponse follow(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var name = Hashtags.parse(id).orElseThrow(RecordNotFoundException::new);
        follows.follow(viewer, name);
        return tags.build(List.of(name), Optional.of(viewer)).getFirst();
    }

    @PostMapping("/{id}/unfollow")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "unfollowTag",
            summary = "Unfollow a hashtag",
            description = """
                    Stops the tag adding statuses to the user's home timeline, including those it already added. \
                    Unfollowing a tag that isn't followed has no effect.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "tags/#unfollow"),
            parameters = @Parameter(name = "id", description = NAME),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The tag, no longer followed."),
                    @ApiResponse(responseCode = "404", description = "The name isn't a valid tag name."),
                    @ApiResponse(responseCode = "422", description = "The token does not identify a user.")
            })
    public TagResponse unfollow(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var name = Hashtags.parse(id).orElseThrow(RecordNotFoundException::new);
        follows.unfollow(viewer, name);
        return tags.build(List.of(name), Optional.of(viewer)).getFirst();
    }
}
