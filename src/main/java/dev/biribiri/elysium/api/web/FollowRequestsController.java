package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import dev.biribiri.elysium.graph.FollowService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/follow_requests")
@Tag(name = "Follow requests", description = "Approving or refusing requests to follow the user, while `locked` is on.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "follow_requests/"))
public class FollowRequestsController {

    private static final String NO_REQUEST = """
            The account isn't waiting for an answer, isn't found, or is suspended, or the ID isn't a UUID.""";

    private final ActorService actors;
    private final FollowService follows;
    private final AccountResponses accounts;
    private final InstanceProperties instance;

    FollowRequestsController(
            ActorService actors, FollowService follows, AccountResponses accounts, InstanceProperties instance) {
        this.actors = actors;
        this.follows = follows;
        this.accounts = accounts;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:follows')")
    @Operation(
            operationId = "getFollowRequests",
            summary = "List the accounts waiting for the user to approve them as followers",
            description = """
                    Differences from Mastodon: pages are by when each request was made, and the cursors are the IDs in \
                    the `Link` header, not account IDs. Suspended and deleted accounts are left out.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "follow_requests/#get"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + FollowPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + FollowPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts, most recent request first."),
                    @ApiResponse(responseCode = "422", description = "A cursor isn't a UUID, or the token has no user.")
            })
    public ResponseEntity<List<AccountResponse>> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = viewerAccount(token);
        var page = RequestParameters.followPage(limit, maxId, sinceId, minId);
        var entries = follows.requests(viewer.id(), page);
        var found = actors.findByIds(entries.stream().map(FollowEntry::actorId).toList()).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var listed = entries.stream().map(FollowEntry::actorId).filter(found::containsKey).map(found::get).toList();
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, "/api/v1/follow_requests", Map.of(), page.limit(),
                entries.stream().map(FollowEntry::followId).toList());
        return response.body(accounts.build(listed));
    }

    @PostMapping("/{account_id}/authorize")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "acceptFollowRequest",
            summary = "Approve a request to follow the user",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "follow_requests/#accept"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = NO_REQUEST),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse authorize(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable("account_id") String accountId) {
        var viewer = viewerAccount(token);
        var follower = activeAccount(accountId);
        if (!follows.authorize(follower, viewer.id())) {
            throw new RecordNotFoundException();
        }
        return relationship(viewer, follower);
    }

    @PostMapping("/{account_id}/reject")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "rejectFollowRequest",
            summary = "Refuse a request to follow the user",
            description = """
                    Differences from Mastodon: an account on another server that sends the same `Follow` again isn't \
                    asking again, so it stays refused. A new `Follow` is a new request.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "follow_requests/#reject"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = NO_REQUEST),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse reject(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable("account_id") String accountId) {
        var viewer = viewerAccount(token);
        var follower = activeAccount(accountId);
        if (!follows.reject(follower, viewer.id())) {
            throw new RecordNotFoundException();
        }
        return relationship(viewer, follower);
    }

    private Actor activeAccount(String id) {
        return RequestParameters.uuid(id)
                .flatMap(actors::findById)
                .filter(Actor::isActive)
                .orElseThrow(RecordNotFoundException::new);
    }

    private Actor viewerAccount(TokenPrincipal token) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        return actors.findLocalById(actorId).orElseThrow(InvalidRequestException::requiresUser);
    }

    private RelationshipResponse relationship(Actor viewer, Actor other) {
        return RelationshipResponse.from(follows.relationships(viewer.id(), List.of(other.id())).getFirst());
    }
}
