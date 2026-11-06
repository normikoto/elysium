package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.auth.AccountActions;
import dev.biribiri.elysium.auth.TokenPrincipal;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
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
    public AdminAccountResponse get(@PathVariable String id) {
        return response(find(id));
    }

    @PostMapping(path = "/{id}/action", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    public Map<String, Object> actionFromForm(
            @AuthenticationPrincipal TokenPrincipal token,
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form) {
        return action(token, id, form.toSingleValueMap());
    }

    @PostMapping(path = "/{id}/action", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    // The API docs combine both mappings into one operation, and this replaces the body documented by the other one.
    @RequestBody(required = true, content = {
            @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(type = "object"))
    })
    public Map<String, Object> actionFromJson(
            @AuthenticationPrincipal TokenPrincipal token, @PathVariable String id, HttpServletRequest request)
            throws IOException {
        return action(token, id, json.read(request, MAX_BODY_BYTES));
    }

    @PostMapping("/{id}/unsuspend")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    public AdminAccountResponse unsuspend(@PathVariable String id) {
        var account = find(id);
        accounts.unsuspend(account.id());
        return response(find(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:accounts')")
    public AdminAccountResponse delete(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
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
}
