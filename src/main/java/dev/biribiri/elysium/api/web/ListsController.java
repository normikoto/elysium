package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.AccountList;
import dev.biribiri.elysium.graph.AccountLists;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping(ListsController.PATH)
@Tag(name = "Lists", description = "Groups of followed accounts, each with a timeline of its own.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/"))
public class ListsController {

    static final String PATH = "/api/v1/lists";

    /**
     * Room for more account IDs than anyone would add at once.
     */
    private static final long MAX_BODY_BYTES = 64 * 1024;

    private static final String LIMITS = "Differences from Mastodon: a title can be up to "
            + AccountLists.MAX_TITLE_LENGTH + " characters, which Mastodon doesn't limit. A user can have up to "
            + AccountLists.MAX_LISTS + " lists, as on Mastodon.";

    private final AccountLists lists;
    private final ActorService actors;
    private final AccountResponses accounts;
    private final JsonBodies json;
    private final InstanceProperties instance;

    ListsController(AccountLists lists, ActorService actors, AccountResponses accounts, JsonBodies json,
                    InstanceProperties instance) {
        this.lists = lists;
        this.actors = actors;
        this.accounts = accounts;
        this.json = json;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:lists')")
    @Operation(
            operationId = "listLists",
            summary = "View the user's lists",
            description = "Oldest first.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The lists."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<ListResponse> list(@AuthenticationPrincipal TokenPrincipal token) {
        return lists.all(viewer(token)).stream().map(ListResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:lists')")
    @Operation(
            operationId = "getList",
            summary = "View a list",
            description = "Another user's list gets a 404, as on Mastodon.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The list."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public ListResponse get(@AuthenticationPrincipal TokenPrincipal token,
                            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(listId -> lists.find(viewer, listId)).map(ListResponse::from)
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_write:lists')")
    @Operation(
            operationId = "createList",
            summary = "Create a list",
            description = LIMITS + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new list."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The title is missing, blank, or too long, `replies_policy` isn't known, the user has as \
                            many lists as they can, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NewListBody.class)))
    public ListResponse create(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var viewer = viewer(token);
        var parameters = json.readAny(request, MAX_BODY_BYTES);
        return ListResponse.from(lists.create(viewer, RequestParameters.string(parameters, "title"),
                repliesPolicy(parameters), FilterParameters.flag(parameters, "exclusive")));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:lists')")
    @Operation(
            operationId = "updateList",
            summary = "Update a list",
            description = "What's left out stays as it is.\n\n" + LIMITS + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The list, as changed."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The title is blank or too long, `replies_policy` isn't known, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ListChangeBody.class)))
    public ListResponse update(@AuthenticationPrincipal TokenPrincipal token,
                               @Parameter(description = ApiDocs.ID) @PathVariable String id,
                               HttpServletRequest request) throws IOException {
        var viewer = viewer(token);
        var listId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var parameters = json.readAny(request, MAX_BODY_BYTES);
        return lists.update(viewer, listId, RequestParameters.string(parameters, "title"), repliesPolicy(parameters),
                        FilterParameters.flag(parameters, "exclusive"))
                .map(ListResponse::from).orElseThrow(RecordNotFoundException::new);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:lists')")
    @Operation(
            operationId = "deleteList",
            summary = "Delete a list",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> delete(@AuthenticationPrincipal TokenPrincipal token,
                                      @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        if (!RequestParameters.uuid(id).map(listId -> lists.delete(viewer, listId)).orElse(false)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @GetMapping("/{id}/accounts")
    @PreAuthorize("hasAuthority('SCOPE_read:lists')")
    @Operation(
            operationId = "getListAccounts",
            summary = "View accounts in a list",
            description = """
                    Includes accounts that the user has asked to follow, whose statuses aren't listed until they \
                    accept. A `limit` of 0 lists every account, with no `Link` header, as on Mastodon.

                    Differences from Mastodon: pages are by when each account was added, and the cursors are the IDs \
                    in the `Link` header, not account IDs. Suspended and deleted accounts are left out.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#accounts"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return, or 0 for all of them. Other values "
                            + "outside 1 to " + FollowPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + FollowPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts, most recently added first."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "A cursor isn't a UUID, or the token has no user.")
            })
    public ResponseEntity<List<AccountResponse>> accounts(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = viewer(token);
        var listId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        if ("0".equals(limit)) {
            var entries = lists.allMembers(viewer, listId).orElseThrow(RecordNotFoundException::new);
            return ResponseEntity.ok(build(entries));
        }
        var page = RequestParameters.followPage(limit, maxId, sinceId, minId);
        var entries = lists.members(viewer, listId, page).orElseThrow(RecordNotFoundException::new);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH + "/" + listId + "/accounts", Map.of(), page.limit(),
                entries.stream().map(FollowEntry::followId).toList());
        return response.body(build(entries));
    }

    @PostMapping("/{id}/accounts")
    @PreAuthorize("hasAuthority('SCOPE_write:lists')")
    @Operation(
            operationId = "addListAccounts",
            summary = "Add accounts to a list",
            description = """
                    The user has to follow each account, or have asked to. All of the accounts are added, or none.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#accounts-add"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = """
                            The list isn't found, or an account isn't, or the user doesn't follow it, or an ID isn't a \
                            UUID, as on Mastodon."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            An account is already in the list, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AccountIdsBody.class)))
    public Map<String, Object> addAccounts(@AuthenticationPrincipal TokenPrincipal token,
                                           @Parameter(description = ApiDocs.ID) @PathVariable String id,
                                           HttpServletRequest request) throws IOException {
        var viewer = viewer(token);
        var listId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var accountIds = accountIds(json.readAny(request, MAX_BODY_BYTES));
        if (!lists.addMembers(viewer, listId, accountIds)) throw new RecordNotFoundException();
        return Map.of();
    }

    @DeleteMapping("/{id}/accounts")
    @PreAuthorize("hasAuthority('SCOPE_write:lists')")
    @Operation(
            operationId = "removeListAccounts",
            summary = "Remove accounts from a list",
            description = "Accounts that aren't in the list are ignored, as on Mastodon.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "lists/#accounts-remove"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = "The list isn't found, or an ID isn't a UUID."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AccountIdsBody.class)))
    public Map<String, Object> removeAccounts(@AuthenticationPrincipal TokenPrincipal token,
                                              @Parameter(description = ApiDocs.ID) @PathVariable String id,
                                              HttpServletRequest request) throws IOException {
        var viewer = viewer(token);
        var listId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var accountIds = accountIds(json.readAny(request, MAX_BODY_BYTES));
        if (!lists.removeMembers(viewer, listId, accountIds)) throw new RecordNotFoundException();
        return Map.of();
    }

    private List<AccountResponse> build(List<FollowEntry> entries) {
        var found = actors.findByIds(entries.stream().map(FollowEntry::actorId).toList()).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        return accounts.build(entries.stream().map(FollowEntry::actorId).filter(found::containsKey).map(found::get)
                .toList());
    }

    private static UUID viewer(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    /**
     * An empty value is the same as a missing one, as in Rails.
     */
    private static AccountList.RepliesPolicy repliesPolicy(Map<String, ?> parameters) {
        var value = RequestParameters.string(parameters, "replies_policy");
        if (value == null) return null;
        return AccountList.RepliesPolicy.parse(value)
                .orElseThrow(() -> new InvalidRequestException("'" + value + "' is not a valid replies_policy"));
    }

    /**
     * A form names them {@code account_ids[]}, and JSON {@code account_ids}. None at all does nothing, as on Mastodon. An
     * ID that isn't a UUID can't be an account, which Mastodon answers with a 404.
     */
    static List<UUID> accountIds(Map<String, ?> parameters) {
        var value = parameters.containsKey("account_ids") ? parameters.get("account_ids") : parameters.get("account_ids[]");
        var values = switch (value) {
            case null -> List.of();
            case List<?> list -> list;
            case String string -> List.of(string);
            default -> throw new InvalidRequestException("Validation failed: account_ids is not valid");
        };
        var ids = new ArrayList<UUID>();
        for (var entry : values) {
            if (!(entry instanceof String string)) {
                throw new InvalidRequestException("Validation failed: account_ids is not valid");
            }
            ids.add(RequestParameters.uuid(string).orElseThrow(RecordNotFoundException::new));
        }
        return ids;
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewList")
    private record NewListBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Up to "
                    + AccountLists.MAX_TITLE_LENGTH + " characters.") String title,
            @Schema(allowableValues = {"followed", "list", "none"}, defaultValue = "list", description = """
                    Which replies to show, besides replies to the author and to the user: to anyone the user \
                    follows, to the list's members, or none.""") String replies_policy,
            @Schema(description = "Whether to leave the members' statuses out of the home timeline.",
                    defaultValue = "false") Boolean exclusive) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "ListChange")
    private record ListChangeBody(
            String title,
            @Schema(allowableValues = {"followed", "list", "none"}) String replies_policy,
            Boolean exclusive) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "ListAccounts")
    private record AccountIdsBody(
            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The accounts' IDs. Sent as `account_ids[]` in a form.""")) List<String> account_ids) {
    }
}
