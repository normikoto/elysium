package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.auth.AccountActions;
import dev.biribiri.elysium.auth.TokenPrincipal;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows Mastodon's admin API for accounts, as far as what's here can. Suspending is the only action, and an account doesn't
 * have to be suspended before it's deleted.
 */
@RestController
@RequestMapping(AdminAccountsController.PATH)
@Tag(name = "Admin: accounts", description = """
        Suspending and deleting accounts. Needs an admin's token. There's no `/api/v2/admin/accounts`.""",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/accounts/"))
public class AdminAccountsController {

    static final String PATH = "/api/v1/admin/accounts";

    private static final long MAX_BODY_BYTES = 4 * 1024;

    private final ActorService actors;
    private final AccountActions accounts;
    private final InstanceProperties instance;
    private final JsonBodies json;
    private final AccountResponses accountResponses;

    AdminAccountsController(
            ActorService actors,
            AccountActions accounts,
            InstanceProperties instance,
            JsonBodies json,
            AccountResponses accountResponses) {
        this.accountResponses = accountResponses;
        this.actors = actors;
        this.accounts = accounts;
        this.instance = instance;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_admin:read:accounts')")
    @Operation(
            operationId = "listAdminAccounts",
            summary = "List accounts",
            description = """
                    Differences from Mastodon: only `local`, `remote`, `suspended`, `username`, and `by_domain` filter \
                    the list, and other filters are ignored. Accounts are ordered by their IDs, which are random, \
                    rather than by age, from highest to lowest, and the cursors follow that order. Deleted accounts \
                    aren't listed.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/accounts/#v1"),
            parameters = {
                    @Parameter(name = "local", description = "Only accounts on this server. " + ApiDocs.FLAG),
                    @Parameter(name = "remote", description = """
                            Only accounts on other servers. Asking for both `local` and `remote` lists both. \
                            """ + ApiDocs.FLAG),
                    @Parameter(name = "suspended", description = "Only suspended accounts. " + ApiDocs.FLAG),
                    @Parameter(name = "username", description = "Only accounts whose username starts with this."),
                    @Parameter(name = "by_domain", description = "Only accounts on this domain."),
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + AccountFilter.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + AccountFilter.DEFAULT_LIMIT)),
                    // The generic cursor descriptions say older and newer, which these IDs don't mean.
                    @Parameter(name = "max_id", description = "Only accounts with an ID lower than this."),
                    @Parameter(name = "since_id", description = "Only accounts with an ID higher than this."),
                    @Parameter(name = "min_id", description = """
                            Only accounts with an ID higher than this, starting with the closest to it.""")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts.")
            })
    public ResponseEntity<List<AdminAccountResponse>> list(
            @RequestParam(required = false) String local,
            @RequestParam(required = false) String remote,
            @RequestParam(required = false) String suspended,
            @RequestParam(required = false) String username,
            @RequestParam(name = "by_domain", required = false) String byDomain,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var filter = RequestParameters.accountFilter(
                RequestParameters.flag(local),
                RequestParameters.flag(remote),
                RequestParameters.flag(suspended),
                username,
                byDomain,
                limit,
                maxId,
                sinceId,
                minId);
        var found = actors.list(filter);
        var filters = new LinkedHashMap<String, String>();
        if (RequestParameters.flag(local)) {
            filters.put("local", "true");
        }
        if (RequestParameters.flag(remote)) {
            filters.put("remote", "true");
        }
        if (filter.suspendedOnly()) {
            filters.put("suspended", "true");
        }
        if (filter.usernamePrefix() != null) {
            filters.put("username", filter.usernamePrefix());
        }
        if (filter.domain() != null) {
            filters.put("by_domain", filter.domain());
        }
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH, filters, filter.limit(), found.stream().map(Actor::id).toList());
        return response.body(responses(found));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin:read:accounts')")
    @Operation(
            operationId = "getAdminAccount",
            summary = "View an account",
            description = "A deleted account gets a 404.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/accounts/#get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public AdminAccountResponse get(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return response(find(id));
    }

    @PostMapping(path = "/{id}/action", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    public Map<String, Object> actionFromForm(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form) {
        return action(token, id, form.toSingleValueMap());
    }

    @PostMapping(path = "/{id}/action", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "actOnAccount",
            summary = "Suspend an account",
            description = """
                    Differences from Mastodon: `type` has to be `suspend`, and other values get a 422, rather than \
                    doing more than an admin meant to. Other parameters are ignored. Suspending yourself, or the \
                    server's last admin, gets a 403, with the reason in `error`.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/accounts/#action"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "403", description = """
                            The token has none of the scopes listed, or the account is the admin's own or the last \
                            admin's."""),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is deleted."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = "`type` isn't `suspend`.")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AccountActionBody.class)))
    public Map<String, Object> actionFromJson(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request)
            throws IOException {
        return action(token, id, json.read(request, MAX_BODY_BYTES));
    }

    @PostMapping("/{id}/unsuspend")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    @Operation(
            operationId = "unsuspendAccount",
            summary = "End an account's suspension",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/accounts/#unsuspend"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The account."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is deleted.")
            })
    public AdminAccountResponse unsuspend(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var account = find(id);
        accounts.unsuspend(account.id());
        return response(find(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    @Operation(
            operationId = "deleteAccount",
            summary = "Delete an account",
            description = """
                    Differences from Mastodon: the account doesn't have to be suspended first. Only accounts on this \
                    server can be deleted, and one on another server gets a 403. Deleting yourself, or the server's \
                    last admin, gets a 403, with the reason in `error`.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/accounts/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The account, as it was deleted."),
                    @ApiResponse(responseCode = "403", description = """
                            The token has none of the scopes listed, or the account is on another server, or is the \
                            admin's own or the last admin's."""),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is already deleted.")
            })
    public AdminAccountResponse delete(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var account = find(id);
        accounts.delete(account.id(), token.actorId());
        return response(actors.findById(account.id()).orElse(account));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> refused(AccountActionRefusedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    /**
     * Mastodon's other actions aren't implemented. Suspending instead would do more than an admin meant to.
     */
    private Map<String, Object> action(TokenPrincipal token, String id, Map<String, ?> parameters) {
        var account = find(id);
        if (!"suspend".equals(RequestParameters.string(parameters, "type"))) {
            throw new InvalidRequestException("Validation failed: type has to be suspend, the only one supported");
        }
        accounts.suspend(account.id(), token.actorId());
        return Map.of();
    }

    /**
     * A deleted account can't be managed any more, so it's treated as gone.
     */
    private Actor find(String id) {
        return RequestParameters.uuid(id)
                .flatMap(actors::findById)
                .filter(account -> !account.isDeleted())
                .orElseThrow(RecordNotFoundException::new);
    }

    private AdminAccountResponse response(Actor account) {
        return responses(List.of(account)).getFirst();
    }

    private List<AdminAccountResponse> responses(Collection<Actor> found) {
        var admins = accounts.adminsAmong(found.stream().map(Actor::id).toList());
        var built = accountResponses.byId(found);
        return found.stream()
                .map(account -> from(account, built.get(account.id()), admins.contains(account.id())))
                .toList();
    }

    private AdminAccountResponse from(Actor account, AccountResponse response, boolean admin) {
        var local = actors.isLocal(account);
        return new AdminAccountResponse(
                account.id().toString(),
                account.username(),
                local ? null : account.domain(),
                ApiTimestamps.format(account.createdAt()),
                local ? new AdminAccountResponse.Role(admin ? "Admin" : "User") : null,
                !account.isActive(),
                response);
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "AccountAction")
    private record AccountActionBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "suspend")
            String type) {
    }
}
