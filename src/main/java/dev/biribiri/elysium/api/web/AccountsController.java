package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
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
public class AccountsController {

    private static final long MAX_CREDENTIALS_BODY_BYTES = 64 * 1024;

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
    // The API docs combine both mappings into one operation, and this replaces the body documented by the other one.
    @RequestBody(required = true, content = {
            @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(type = "object"))
    })
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
    public AccountResponse lookup(@AuthenticationPrincipal TokenPrincipal token, @RequestParam(required = false) String acct) {
        if (acct == null || acct.isEmpty()) {
            throw new RecordNotFoundException();
        }
        return lookup.byAcct(acct, RequestParameters.viewer(token), true)
                .map(accounts::one)
                .orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/relationships")
    @PreAuthorize("hasAuthority('SCOPE_read:follows')")
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
    public AccountResponse account(@PathVariable String id) {
        return accounts.one(activeAccount(id));
    }

    /**
     * Mastodon's {@code reblogs}, {@code notify} and {@code languages} have nothing to apply to yet, so they're ignored.
     */
    @PostMapping("/{id}/follow")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    public RelationshipResponse follow(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
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
    public RelationshipResponse unfollow(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var follower = viewerAccount(token);
        var followed = RequestParameters.uuid(id).flatMap(actors::findById).orElseThrow(RecordNotFoundException::new);
        follows.unfollow(follower, followed.id());
        return relationship(follower.id(), followed.id());
    }

    @GetMapping("/{id}/followers")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    public ResponseEntity<List<AccountResponse>> followers(
            @AuthenticationPrincipal TokenPrincipal token,
            @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        return follows(token, id, true, RequestParameters.followPage(limit, maxId, sinceId, minId));
    }

    @GetMapping("/{id}/following")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    public ResponseEntity<List<AccountResponse>> following(
            @AuthenticationPrincipal TokenPrincipal token,
            @PathVariable String id,
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
    public ResponseEntity<List<StatusResponse>> statuses(
            @AuthenticationPrincipal TokenPrincipal token,
            @PathVariable String id,
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
}
