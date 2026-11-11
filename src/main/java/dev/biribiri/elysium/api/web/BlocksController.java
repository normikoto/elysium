package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping(BlocksController.PATH)
@Tag(name = "Blocks", description = "The accounts the user blocks. Accounts are blocked through the accounts API.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "blocks/"))
public class BlocksController {

    static final String PATH = "/api/v1/blocks";

    private final ActorService actors;
    private final BlockService blocks;
    private final AccountResponses accounts;
    private final InstanceProperties instance;

    BlocksController(ActorService actors, BlockService blocks, AccountResponses accounts, InstanceProperties instance) {
        this.actors = actors;
        this.blocks = blocks;
        this.accounts = accounts;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:blocks')")
    @Operation(
            operationId = "getBlocks",
            summary = "List the accounts the user blocks",
            description = """
                    Differences from Mastodon: pages are by when each block was made, and the cursors are the IDs in \
                    the `Link` header, not account IDs. Suspended and deleted accounts are left out.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "blocks/#get"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + FollowPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + FollowPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts, most recent block first."),
                    @ApiResponse(responseCode = "422", description = "A cursor isn't a UUID, or the token has no user.")
            })
    public ResponseEntity<List<AccountResponse>> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var page = RequestParameters.followPage(limit, maxId, sinceId, minId);
        var entries = blocks.blocked(viewer, page);
        var found = actors.findByIds(entries.stream().map(FollowEntry::actorId).toList()).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var listed = entries.stream().map(FollowEntry::actorId).filter(found::containsKey).map(found::get).toList();
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH, Map.of(), page.limit(),
                entries.stream().map(FollowEntry::followId).toList());
        return response.body(accounts.build(listed));
    }
}
