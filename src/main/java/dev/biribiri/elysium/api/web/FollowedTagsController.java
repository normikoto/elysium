package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.FollowedTag;
import dev.biribiri.elysium.graph.TagFollows;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping(FollowedTagsController.PATH)
@Tag(name = "Followed tags", description = """
        The hashtags the user follows. Tags are followed through the tags API.""",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "followed_tags/"))
public class FollowedTagsController {

    static final String PATH = "/api/v1/followed_tags";

    private final TagFollows follows;
    private final TagResponses tags;
    private final InstanceProperties instance;

    FollowedTagsController(TagFollows follows, TagResponses tags, InstanceProperties instance) {
        this.follows = follows;
        this.tags = tags;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:follows')")
    @Operation(
            operationId = "getFollowedTags",
            summary = "List the hashtags the user follows",
            description = """
                    The cursors are the IDs in the `Link` header, as on Mastodon, not tag names. `history` only \
                    counts public statuses stored on this server.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "followed_tags/#get"),
            parameters = {
                    @Parameter(name = "limit", description = "Maximum number of results. Values outside 1 to "
                            + PageLimits.MAX_FOLLOWED_TAGS + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + PageLimits.FOLLOWED_TAGS))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The tags, most recently followed first."),
                    @ApiResponse(responseCode = "422", description = "A cursor is not a UUID, or the token has no user.")
            })
    public ResponseEntity<List<TagResponse>> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var page = RequestParameters.followedTagPage(limit, maxId, sinceId, minId);
        var followed = follows.followed(viewer, page);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH, Map.of(), page.limit(), followed.stream().map(FollowedTag::id).toList());
        return response.body(tags.build(followed.stream().map(FollowedTag::name).toList(), Optional.of(viewer)));
    }
}
