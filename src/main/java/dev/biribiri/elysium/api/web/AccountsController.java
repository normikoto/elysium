package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Viewing accounts, and following them.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/"))
public class AccountsController {

    private static final long MAX_CREDENTIALS_BODY_BYTES = 64 * 1024;

    private static final String FOLLOWS = """
            Differences from Mastodon: pages are by when each follow was made, so a cursor from a follow that's since been \
            removed gives an empty page. For an account on another server, only accounts on this server are listed. \
            Suspended accounts are left out.

            If the account hides who's in its collections, anyone else gets an empty list, as on Mastodon. A suspended \
            account gets a 404.""";

    private static final String FOLLOW_LIMIT =
            "How many to return. Values outside 1 to " + FollowPage.MAX_LIMIT + " are clamped to them.";

    private final ActorService actors;
    private final StatusService statuses;
    private final StatusResponses responses;
    private final AccountResponses accounts;
    private final FollowService follows;
    private final DomainBlocks blocks;
    private final AccountLookup lookup;
    private final JsonBodies json;
    private final InstanceProperties instance;

    AccountsController(
            ActorService actors,
            StatusService statuses,
            StatusResponses responses,
            AccountResponses accounts,
            FollowService follows,
            DomainBlocks blocks,
            AccountLookup lookup,
            JsonBodies json,
            InstanceProperties instance) {
        this.actors = actors;
        this.statuses = statuses;
        this.responses = responses;
        this.accounts = accounts;
        this.follows = follows;
        this.blocks = blocks;
        this.lookup = lookup;
        this.json = json;
        this.instance = instance;
    }

    @GetMapping("/verify_credentials")
    @PreAuthorize("hasAnyAuthority('SCOPE_profile', 'SCOPE_read:accounts')")
    @Operation(
            operationId = "verifyAccountCredentials",
            summary = "Get the user's own account",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#verify_credentials"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The user's account."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public ResponseEntity<CredentialAccountResponse> verifyCredentials(@AuthenticationPrincipal TokenPrincipal token) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        return ResponseEntity.of(actors.findLocalById(actorId)
                .map(actor -> CredentialAccountResponse.from(accounts.one(actor))));
    }

    /**
     * Only {@code hide_collections} can be changed, because profiles don't exist yet. Anything else that would change the
     * account is refused, rather than ignored, so that a client doesn't tell someone their profile was saved. An empty value
     * counts as missing, because form clients send fields they haven't filled in.
     */
    @PatchMapping(path = "/update_credentials",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    public CredentialAccountResponse updateCredentialsFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @RequestParam MultiValueMap<String, String> form) {
        var values = new LinkedHashMap<String, Object>();
        form.forEach((name, list) -> values.put(name, list.isEmpty() ? null : list.getLast()));
        return updateCredentials(token, values);
    }

    @PatchMapping(path = "/update_credentials", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "updateAccountCredentials",
            summary = "Change the user's account settings",
            description = """
                    Differences from Mastodon: only `hide_collections` can be changed, because profiles don't exist \
                    yet. Any other field with a value gets a 422 naming it, and then nothing is changed. Empty values \
                    are ignored.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#update_credentials"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The user's account, as changed."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            A field other than `hide_collections` has a value, a value is of the wrong type, or the \
                            token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CredentialsBody.class)))
    public CredentialAccountResponse updateCredentialsFromJson(
            @AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request) throws IOException {
        return updateCredentials(token, json.read(request, MAX_CREDENTIALS_BODY_BYTES));
    }

    private CredentialAccountResponse updateCredentials(TokenPrincipal token, Map<String, ?> values) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        Boolean hide = null;
        for (var entry : values.entrySet()) {
            var value = entry.getValue();
            if (value == null || "".equals(value)) {
                continue;
            }
            if (!entry.getKey().equals("hide_collections")) {
                throw new InvalidRequestException("Validation failed: " + entry.getKey() + " can't be changed yet");
            }
            hide = RequestParameters.flag(values, "hide_collections");
        }
        if (hide != null) {
            actors.setHidesCollections(actorId, hide);
        }
        return actors.findLocalById(actorId)
                .map(actor -> CredentialAccountResponse.from(accounts.one(actor)))
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * Finds an account by its username, and its domain if it's on another server. Only a signed-in user's request can make
     * this server ask another one about an account it doesn't know yet, or hasn't refreshed for a while.
     */
    @GetMapping("/lookup")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "lookupAccount",
            summary = "Find an account by its address",
            description = """
                    Only asks another server about an account for a signed-in user's request, and so finds only the \
                    accounts this server already knows otherwise. Each user can make 30 of those lookups a minute, \
                    and then gets a 429. A suspended or deleted account isn't found.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#lookup"),
            parameters = @Parameter(name = "acct", required = true, description = """
                    The username of an account on this server, or `username@domain` for one on another server. A \
                    missing or empty value gets a 404."""),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The account."),
                    @ApiResponse(responseCode = "404", description = "No account was found."),
                    @ApiResponse(responseCode = "429", description = "The user has made too many lookups.")
            })
    public AccountResponse lookup(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String acct) {
        if (acct == null || acct.isEmpty()) {
            throw new RecordNotFoundException();
        }
        return lookup.byAcct(acct, RequestParameters.viewer(token), true)
                .map(accounts::one)
                .orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/relationships")
    @PreAuthorize("hasAuthority('SCOPE_read:follows')")
    @Operation(
            operationId = "getRelationships",
            summary = "Check the user's relationships with accounts",
            description = """
                    Differences from Mastodon: blocks, mutes, notes, and endorsements don't exist, so those fields are \
                    always false or empty, and `showing_reblogs` is true whenever `following` or `requested` is. \
                    Suspended accounts are always left out, and `with_suspended` is ignored. Unknown and malformed \
                    IDs are left out, as on Mastodon.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#relationships"),
            parameters = {
                    @Parameter(name = "id[]", description = "The accounts' IDs, one value each."),
                    @Parameter(name = "id", description = "The same as `id[]`, for clients that leave out the brackets.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "A relationship for each account found."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<RelationshipResponse> relationships(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(name = "id[]", required = false) List<String> ids,
            @RequestParam(name = "id", required = false) List<String> plainIds) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var requested = new ArrayList<String>();
        Optional.ofNullable(ids).ifPresent(requested::addAll);
        Optional.ofNullable(plainIds).ifPresent(requested::addAll);
        // Unknown and malformed IDs are left out, as Mastodon does.
        var parsed = requested.stream().distinct().flatMap(id -> RequestParameters.uuid(id).stream()).toList();
        var active = actors.findByIds(parsed).stream().filter(Actor::isActive).map(Actor::id).collect(Collectors.toSet());
        var found = parsed.stream().filter(active::contains).toList();
        return follows.relationships(viewer, found).stream().map(RelationshipResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getAccount",
            summary = "View an account",
            description = "A suspended or deleted account gets a 404.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public AccountResponse account(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return accounts.one(activeAccount(id));
    }

    /**
     * Mastodon's {@code reblogs}, {@code notify} and {@code languages} have nothing to apply to yet, so they're ignored.
     */
    @PostMapping("/{id}/follow")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "followAccount",
            summary = "Follow an account",
            description = """
                    Differences from Mastodon:

                    - Following an account on this server takes effect straight away. Following one on another server \
                    returns `requested: true` until that server accepts.
                    - Following yourself, or an account on a blocked server, gets a 422.
                    - `reblogs`, `notify`, and `languages` are ignored.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#follow"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is suspended."),
                    @ApiResponse(responseCode = "422", description = """
                            The account is the user's own, or is on a blocked server, or the token has no user.""")
            })
    public RelationshipResponse follow(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var follower = viewerAccount(token);
        var followed = activeAccount(id);
        if (followed.id().equals(follower.id())) {
            throw new InvalidRequestException("Validation failed: you can't follow yourself");
        }
        if (!actors.isLocal(followed) && blocks.isBlocked(followed.uri().getHost())) {
            throw new InvalidRequestException("Validation failed: this account's server is blocked");
        }
        follows.follow(follower, followed);
        return relationship(follower.id(), followed.id());
    }

    /**
     * An account can be unfollowed whatever its state, so that someone can stop following a suspended one.
     */
    @PostMapping("/{id}/unfollow")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "unfollowAccount",
            summary = "Unfollow an account",
            description = """
                    Also withdraws a request to follow that's still waiting. An account can be unfollowed while it's \
                    suspended.\s""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#unfollow"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse unfollow(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var follower = viewerAccount(token);
        var followed = RequestParameters.uuid(id).flatMap(actors::findById).orElseThrow(RecordNotFoundException::new);
        follows.unfollow(follower, followed.id());
        return relationship(follower.id(), followed.id());
    }

    @GetMapping("/{id}/followers")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getAccountFollowers",
            summary = "List the accounts following an account",
            description = FOLLOWS,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#followers"),
            parameters = {
                    @Parameter(name = "limit", description = FOLLOW_LIMIT,
                            schema = @Schema(type = "integer", defaultValue = "" + FollowPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts, most recent follow first."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public ResponseEntity<List<AccountResponse>> followers(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        return follows(token, id, true, RequestParameters.followPage(limit, maxId, sinceId, minId));
    }

    @GetMapping("/{id}/following")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getAccountFollowing",
            summary = "List the accounts an account follows",
            description = FOLLOWS,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#following"),
            parameters = {
                    @Parameter(name = "limit", description = FOLLOW_LIMIT,
                            schema = @Schema(type = "integer", defaultValue = "" + FollowPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts, most recent follow first."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public ResponseEntity<List<AccountResponse>> following(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        return follows(token, id, false, RequestParameters.followPage(limit, maxId, sinceId, minId));
    }

    /**
     * An account that hides its collections gets an empty list for anyone but itself, as Mastodon does. A remote account's
     * lists only have the accounts on this server.
     */
    private ResponseEntity<List<AccountResponse>> follows(
            TokenPrincipal token, String id, boolean followers, FollowPage page) {
        var account = activeAccount(id);
        var viewer = RequestParameters.viewer(token);
        var hidden = actors.isLocal(account) && actors.hidesCollections(account.id())
                && viewer.filter(account.id()::equals).isEmpty();
        if (hidden) {
            return ResponseEntity.ok(List.of());
        }
        List<FollowEntry> entries = followers
                ? follows.followers(account.id(), page)
                : follows.following(account.id(), page);
        var found = actors.findByIds(entries.stream().map(FollowEntry::actorId).toList()).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var listed = entries.stream()
                .map(FollowEntry::actorId)
                .filter(found::containsKey)
                .map(found::get)
                .toList();
        var response = ResponseEntity.ok();
        var path = "/api/v1/accounts/" + account.id() + (followers ? "/followers" : "/following");
        PageLinks.addTo(response, instance, path, Map.of(), page.limit(),
                entries.stream().map(FollowEntry::followId).toList());
        return response.body(accounts.build(listed));
    }

    /**
     * Statuses can't be pinned, tagged, or have media yet, so those filters have nothing to return. There are no boosts
     * either, so {@code exclude_reblogs} has no effect.
     */
    @GetMapping("/{id}/statuses")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getAccountStatuses",
            summary = "List an account's statuses",
            description = """
                    Differences from Mastodon: statuses from other servers aren't stored, so an account on another \
                    server gets a 404. Statuses can't be pinned, tagged, or have media, so `pinned`, `tagged`, and \
                    `only_media` return an empty list, and there are no boosts for `exclude_reblogs` to leave out.

                    Only statuses the viewer can see are listed. A suspended account gets a 404.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#statuses"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT)),
                    @Parameter(name = "exclude_replies", description = "Leave out replies. " + ApiDocs.FLAG),
                    @Parameter(name = "exclude_reblogs", description = "Has no effect. " + ApiDocs.FLAG),
                    @Parameter(name = "only_media", description = "Returns an empty list if true. " + ApiDocs.FLAG),
                    @Parameter(name = "pinned", description = "Returns an empty list if true. " + ApiDocs.FLAG),
                    @Parameter(name = "tagged", description = "Returns an empty list if not empty.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, newest first."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is on another server.")
            })
    public ResponseEntity<List<StatusResponse>> statuses(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId,
            @RequestParam(name = "exclude_replies", required = false) String excludeReplies,
            @RequestParam(name = "exclude_reblogs", required = false) String excludeReblogs,
            @RequestParam(name = "only_media", required = false) String onlyMedia,
            @RequestParam(required = false) String pinned,
            @RequestParam(required = false) String tagged) {
        var actorId = RequestParameters.uuid(id)
                .filter(candidate -> actors.findLocalById(candidate).isPresent())
                .orElseThrow(RecordNotFoundException::new);
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var withoutReplies = RequestParameters.flag(excludeReplies);
        var nothingToReturn = RequestParameters.flag(onlyMedia) || RequestParameters.flag(pinned)
                || (tagged != null && !tagged.isEmpty());
        List<Status> found = nothingToReturn
                ? List.of()
                : statuses.findByAccount(actorId, RequestParameters.viewer(token), withoutReplies, page);

        var filters = new LinkedHashMap<String, String>();
        if (withoutReplies) {
            filters.put("exclude_replies", "true");
        }
        if (RequestParameters.flag(excludeReblogs)) {
            filters.put("exclude_reblogs", "true");
        }
        return responses.page("/api/v1/accounts/" + actorId + "/statuses", filters, page, found);
    }

    /**
     * Suspended accounts look the same as ones that don't exist, as they do elsewhere in the API.
     */
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

    private RelationshipResponse relationship(UUID viewer, UUID other) {
        return RelationshipResponse.from(follows.relationships(viewer, List.of(other)).getFirst());
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "AccountCredentials")
    private record CredentialsBody(
            @Schema(description = """
                    Whether to hide who's in the user's followers and following from everyone else. The counts are \
                    still shown.""")
            Boolean hide_collections) {
    }
}
