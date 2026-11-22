package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.PostingDefaults;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.AccountLists;
import dev.biribiri.elysium.graph.AccountEndorsements;
import dev.biribiri.elysium.graph.AccountMemos;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowNotAllowedException;
import dev.biribiri.elysium.graph.FollowOptions;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.MuteService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
@Tag(name = "Accounts", description = "Viewing accounts, and following, blocking, and muting them.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/"))
public class AccountsController {

    /**
     * Mastodon's, which are larger than {@code /api/v2/search}'s, since a client asks for nothing but accounts.
     */
    private static final int DEFAULT_SEARCH_LIMIT = 40;
    private static final int MAX_SEARCH_LIMIT = 80;
    private static final long MAX_CREDENTIALS_BODY_BYTES = 64 * 1024;

    private static final String FOLLOWS = """
            Differences from Mastodon: pages are by when each follow was made, so a cursor from a follow that's since been \
            removed gives an empty page. For an account on another server, only accounts on this server are listed. \
            Suspended and deleted accounts are left out.

            If the account hides who's in its collections, anyone else gets an empty list, as on Mastodon. A suspended \
            account gets a 404.""";

    /**
     * Room for a mute's two parameters, with plenty to spare.
     */
    private static final long MAX_MUTE_BODY_BYTES = 4 * 1024;

    /**
     * Room for a follow's options with a language list longer than any client sends.
     */
    private static final long MAX_FOLLOW_BODY_BYTES = 16 * 1024;

    /**
     * Room for the longest memo in JSON, where each character can be written as two six-byte Unicode escapes. Only JSON
     * bodies are counted against it, and Tomcat's own limit covers forms.
     */
    private static final long MAX_MEMO_BODY_BYTES = 64 * 1024;

    private static final String ENDORSE = """
            Shows the account in `GET /api/v1/endorsements`, and as `endorsed` on the relationship. The user has to \
            follow the account already, or gets a 422, as on Mastodon, and the endorsement ends when the follow does. \
            Endorsing an account again changes nothing. Nothing is sent to other servers.

            """;

    private static final String FOLLOW_LIMIT =
            "How many to return. Values outside 1 to " + PageLimits.MAX_ACCOUNTS + " are clamped to them.";

    private final ActorService actors;
    private final StatusService statuses;
    private final StatusResponses responses;
    private final AccountResponses accounts;
    private final FollowService follows;
    private final BlockService accountBlocks;
    private final MuteService mutes;
    private final DomainBlocks blocks;
    private final AccountLookup lookup;
    private final AccountSearch search;
    private final JsonBodies json;
    private final InstanceProperties instance;
    private final ProfileService profiles;
    private final PostingDefaults postingDefaults;
    private final StatusPinService pins;
    private final AccountLists lists;
    private final AccountMemos memos;
    private final AccountEndorsements endorsements;

    AccountsController(
            ActorService actors,
            StatusService statuses,
            StatusResponses responses,
            AccountResponses accounts,
            FollowService follows,
            BlockService accountBlocks,
            MuteService mutes,
            DomainBlocks blocks,
            AccountLookup lookup,
            AccountSearch search,
            JsonBodies json,
            InstanceProperties instance,
            ProfileService profiles,
            PostingDefaults postingDefaults,
            StatusPinService pins,
            AccountLists lists,
            AccountMemos memos,
            AccountEndorsements endorsements) {
        this.memos = memos;
        this.endorsements = endorsements;
        this.lists = lists;
        this.pins = pins;
        this.profiles = profiles;
        this.postingDefaults = postingDefaults;
        this.actors = actors;
        this.statuses = statuses;
        this.responses = responses;
        this.accounts = accounts;
        this.follows = follows;
        this.accountBlocks = accountBlocks;
        this.mutes = mutes;
        this.blocks = blocks;
        this.lookup = lookup;
        this.search = search;
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
                .map(accounts::credential));
    }

    /**
     * Files only come in a multipart form. Its other values are read along with a URL-encoded form's, with the same names.
     */
    @PatchMapping(path = "/update_credentials",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    public CredentialAccountResponse updateCredentialsFromForm(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam MultiValueMap<String, String> form,
            HttpServletRequest request) {
        // Spring Security wraps the request, so it's only multipart underneath.
        var multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        var files = multipart == null ? Map.<String, MultipartFile>of() : multipart.getFileMap();
        // Before the files are read into memory, since Spring takes files as large as a video.
        if (files.get("avatar") != null) {
            profiles.checkAvatarSize(files.get("avatar").getSize());
        }
        if (files.get("header") != null) {
            profiles.checkHeaderSize(files.get("header").getSize());
        }
        if (files.get("pleroma_background_image") != null) {
            profiles.checkBackgroundSize(files.get("pleroma_background_image").getSize());
        }
        return updateCredentials(token, CredentialsRequest.fromForm(form, files));
    }

    @PatchMapping(path = "/update_credentials", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "updateAccountCredentials",
            summary = "Change the user's profile and account settings",
            description = """
                    Differences from Mastodon: `display_name`, `note`, `fields_attributes`, `avatar`, `header`, \
                    Pleroma's `pleroma_background_image`, `locked`, `indexable`, `hide_collections`, `source[quote_policy]`, and `source[quote_others]` can be changed. `bot`, \
                    `discoverable`, and the rest of `source` are accepted only with the values the account already has \
                    (false, and `public` for `source[privacy]`), and any other value for them, or any other field with \
                    a value, gets a 422 naming it. Then nothing is changed. Empty values are ignored, except that an empty `display_name` or `note` clears it.

                    `fields_attributes` can be a list, or an object keyed by position, as can a form's \
                    `fields_attributes[0][name]` or `fields_attributes[][name]`. It replaces all of the fields, and \
                    those with an empty name and value are left out. The instance's \
                    `configuration.accounts.max_profile_fields` says how many there can be.

                    `avatar` and `header` can only be sent as files in a multipart form, which is where every \
                    field can go. They have to be a PNG, JPEG, GIF, or WebP, and are always re-encoded as WebP, cropped \
                    to 400 × 400 and 1500 × 500. Animated ones stay animated, and `avatar_static` and `header_static` \
                    are their first frame.

                    `pleroma_background_image` is the profile's background, as on Pleroma. It's a file in a \
                    multipart form too, and an empty value instead of a file removes it. It's re-encoded as WebP like \
                    the others, but only scaled down to fit 3840 × 2160 pixels in area, without cropping. It's returned in \
                    `pleroma.background_image`, and isn't sent to other servers.

                    Turning `locked` off approves the requests to follow the user that are still waiting, as on \
                    Mastodon. They're approved in the background, so they can still be waiting just after the response.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#update_credentials"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The user's account, as changed."),
                    @ApiResponse(responseCode = "413", description = "The body, or an image, is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            A value breaks a limit or is of the wrong type, an image can't be used, a field that \
                            can't be changed has a different value, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CredentialsBody.class)))
    public CredentialAccountResponse updateCredentialsFromJson(
            @AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request) throws IOException {
        return updateCredentials(token, CredentialsRequest.fromJson(json.read(request, MAX_CREDENTIALS_BODY_BYTES)));
    }

    /**
     * The profile goes first, since it's what can be refused once the request has been read.
     */
    private CredentialAccountResponse updateCredentials(TokenPrincipal token, CredentialsRequest request) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        if (!profiles.update(actorId, request.profile())) {
            throw new RecordNotFoundException();
        }
        if (request.hideCollections() != null) {
            actors.setHidesCollections(actorId, request.hideCollections());
        }
        if (request.quotePolicy() != null) {
            postingDefaults.setQuotePolicy(actorId, request.quotePolicy());
        }
        if (request.quoteOthers() != null) {
            postingDefaults.setQuoteOthers(actorId, request.quoteOthers());
        }
        return actors.findLocalById(actorId)
                .map(accounts::credential)
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
                    accounts this server already knows otherwise. Each user can make\s"""
                    + RemoteActorResolver.LOOKUPS_PER_MINUTE
                    + " of those lookups a minute, and then gets a 429. A suspended or deleted account isn't found.",
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
                    Differences from Mastodon: suspended accounts are always left out, and `with_suspended` is ignored. Unknown and malformed \
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
        return follows.relationships(viewer, activeAmong(ids, plainIds)).stream().map(RelationshipResponse::from).toList();
    }

    /**
     * @return the active accounts among those asked about by {@code id[]} and {@code id}, in the order asked, leaving out
     * unknown and malformed IDs as Mastodon does
     */
    private List<UUID> activeAmong(List<String> ids, List<String> plainIds) {
        var requested = new ArrayList<String>();
        Optional.ofNullable(ids).ifPresent(requested::addAll);
        Optional.ofNullable(plainIds).ifPresent(requested::addAll);
        var parsed = requested.stream().distinct().flatMap(id -> RequestParameters.uuid(id).stream()).toList();
        var active = actors.findByIds(parsed).stream().filter(Actor::isActive).map(Actor::id).collect(Collectors.toSet());
        return parsed.stream().filter(active::contains).toList();
    }

    @GetMapping("/familiar_followers")
    @PreAuthorize("hasAuthority('SCOPE_read:follows')")
    @Operation(
            operationId = "getFamiliarFollowers",
            summary = "Find which accounts the user follows also follow accounts",
            description = """
                    Differences from Mastodon: for an account on another server, only accounts on this server are \
                    listed. Each list has the most recent follow first.

                    Suspended and deleted followers are left out. If the account hides who's in its collections, its list \
                    is empty, unless it's the user's own. Unknown, malformed, and suspended accounts are left out, as on \
                    Mastodon.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#familiar_followers"),
            parameters = {
                    @Parameter(name = "id[]", description = "The accounts' IDs, one value each."),
                    @Parameter(name = "id", description = "The same as `id[]`, for clients that leave out the brackets.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "An entry for each account found, in the order asked."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<FamiliarFollowersResponse> familiarFollowers(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(name = "id[]", required = false) List<String> ids,
            @RequestParam(name = "id", required = false) List<String> plainIds) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var found = activeAmong(ids, plainIds);
        // As on the followers list, an account that hides it doesn't have it shown to anyone else.
        var hiding = actors.hidingCollections(found);
        var shown = found.stream().filter(id -> id.equals(viewer) || !hiding.contains(id)).toList();
        var followerIds = follows.followersFollowedBy(viewer, shown);
        var followers = accounts.byId(actors.findByIds(
                followerIds.values().stream().flatMap(List::stream).distinct().toList()));
        return found.stream()
                .map(id -> new FamiliarFollowersResponse(id.toString(),
                        followerIds.getOrDefault(id, List.of()).stream()
                                .filter(followers::containsKey)
                                .map(followers::get)
                                .toList()))
                .toList();
    }

    @GetMapping("/search")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "searchAccounts",
            summary = "Search for accounts",
            description = """
                    Differences from Mastodon:

                    - A query that's a `username@domain` finds that one account, and an `https` URL finds the account \
                    stored with that ID, where Mastodon finds nothing. Any other query matches the start of the \
                    usernames this server knows, not display names.
                    - Another server is only asked about an address for a signed-in user's request, when `resolve` is \
                    true and `following` isn't, and never about a URL. Each user can make\s""" + RemoteActorResolver.LOOKUPS_PER_MINUTE + """
                     of those lookups a minute, and then gets a 429.
                    - Only the first\s""" + AccountFilter.MAX_LIMIT + """
                     matches can be paged through with `offset`, and an `offset` past them returns no accounts.

                    Suspended accounts are left out.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#search"),
            parameters = {
                    @Parameter(name = "q", description = """
                            A username, or the start of one, a `username@domain`, or an account's `https` ID. A \
                            missing or blank query returns nothing."""),
                    @Parameter(name = "resolve", description = """
                            Whether to ask another server about an address this server doesn't know, or an account \
                            it hasn't refreshed for a while. Only works for a signed-in user, without `following`.\s""" + ApiDocs.FLAG),
                    @Parameter(name = "following", description = """
                            Whether to only find accounts the user follows. Ignored without a user.\s""" + ApiDocs.FLAG),
                    @Parameter(name = "limit", description = "How many accounts to return. Values outside 1 to "
                            + MAX_SEARCH_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + DEFAULT_SEARCH_LIMIT)),
                    @Parameter(name = "offset", description = "How many matches to skip.",
                            schema = @Schema(type = "integer", defaultValue = "0"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts found."),
                    @ApiResponse(responseCode = "422", description = "`limit` or `offset` isn't a number."),
                    @ApiResponse(responseCode = "429", description = "The user has made too many lookups.")
            })
    public List<AccountResponse> search(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String resolve,
            @RequestParam(required = false) String following,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset) {
        // Read before anything else, so that a malformed request is refused whatever it searches for.
        var pageSize = Math.clamp(RequestParameters.number("limit", limit, DEFAULT_SEARCH_LIMIT), 1, MAX_SEARCH_LIMIT);
        var skipped = Math.max(RequestParameters.number("offset", offset, 0), 0);
        if (q == null || q.isBlank()) {
            return List.of();
        }
        var found = search.find(q.strip(), RequestParameters.viewer(token), RequestParameters.flag(resolve),
                RequestParameters.flag(following), pageSize, skipped);
        return accounts.build(found);
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
     * Following again only changes the options given, as on Mastodon, which is how clients change them.
     */
    @PostMapping("/{id}/follow")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "followAccount",
            summary = "Follow an account",
            description = """
                    Differences from Mastodon:

                    - Following an account on another server returns `requested: true` until that server accepts.
                    - Following a `locked` account here that follows you can return `following: true` straight away, \
                    when its user has chosen to approve requests from people they follow.
                    - Following yourself, or an account on a server this server blocks, gets a 422.

                    Following an account already followed or asked changes only the options given, as on Mastodon. \
                    `reblogs` and `languages` also apply to the user's lists. Following an account that either blocks \
                    the other, or on a server the user has blocked, gets a 403, as on Mastodon.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#follow"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "403", description = """
                            The user blocks the account or its server, or the account blocks the user."""),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is suspended."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The account is the user's own, or is on a blocked server, a parameter is refused, or the \
                            token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FollowBody.class)))
    public RelationshipResponse follow(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        var follower = viewerAccount(token);
        var followed = activeAccount(id);
        if (followed.id().equals(follower.id())) {
            throw new InvalidRequestException("Validation failed: you can't follow yourself");
        }
        if (!actors.isLocal(followed) && blocks.isBlocked(followed.uri().getHost())) {
            throw new InvalidRequestException("Validation failed: this account's server is blocked");
        }
        follows.follow(follower, followed, followOptions(json.readAny(request, MAX_FOLLOW_BODY_BYTES)));
        return relationship(follower.id(), followed.id());
    }

    private static FollowOptions followOptions(Map<String, Object> parameters) {
        return new FollowOptions(
                optionalFlag(parameters, "reblogs"),
                optionalFlag(parameters, "notify"),
                followLanguages(parameters));
    }

    private static Optional<Boolean> optionalFlag(Map<String, Object> parameters, String name) {
        return parameters.containsKey(name) ? Optional.of(RequestParameters.flag(parameters, name)) : Optional.empty();
    }

    /**
     * A form names them {@code languages[]}, and JSON {@code languages}. An empty list, a null, or a form's empty value
     * means every language, as on Mastodon.
     */
    private static Optional<List<String>> followLanguages(Map<String, Object> parameters) {
        var key = parameters.containsKey("languages[]") ? "languages[]" : "languages";
        if (!parameters.containsKey(key)) {
            return Optional.empty();
        }
        List<?> values = switch (parameters.get(key)) {
            case null -> List.of();
            case List<?> list -> list;
            case Object value -> List.of(value);
        };
        var languages = new ArrayList<String>();
        for (var value : values) {
            if (!(value instanceof String language)) {
                throw new InvalidRequestException("Validation failed: languages must be a list of language codes");
            }
            if (language.isEmpty() || languages.contains(language)) {
                continue;
            }
            if (!StatusService.isLanguageCode(language)) {
                throw new InvalidRequestException("Validation failed: " + language + " is not a language code");
            }
            languages.add(language);
        }
        return Optional.of(languages);
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

    /**
     * A follower can be removed whatever its state, as an account can be unfollowed.
     */
    @PostMapping("/{id}/remove_from_followers")
    @PreAuthorize("hasAuthority('SCOPE_write:follows')")
    @Operation(
            operationId = "removeAccountFromFollowers",
            summary = "Remove an account from the user's followers",
            description = """
                    Stops the account following the user, without blocking it, so it can follow again. An account on \
                    another server has its server sent a `Reject` of its `Follow`. A request to follow that's still \
                    waiting isn't affected, and nor is an account that doesn't follow the user. A follower can be \
                    removed while it's suspended.\s""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#remove_from_followers"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse removeFromFollowers(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var owner = viewerAccount(token);
        var follower = RequestParameters.uuid(id).flatMap(actors::findById).orElseThrow(RecordNotFoundException::new);
        follows.removeFollower(owner, follower);
        return relationship(owner.id(), follower.id());
    }

    @PostMapping("/{id}/block")
    @PreAuthorize("hasAuthority('SCOPE_write:blocks')")
    @Operation(
            operationId = "blockAccount",
            summary = "Block an account",
            description = """
                    Removes the follows between the user and the account, both ways, and the notifications between \
                    them, and stops either following the other. The account's statuses are hidden from the user, and \
                    the user's from the account. An account on another server is sent a `Block`, unless the server \
                    is set not to, and its server is told that the follows ended either way. Repeats have no \
                    additional effect.

                    Differences from Mastodon: blocking yourself gets a 422.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#block"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is suspended."),
                    @ApiResponse(responseCode = "422", description = "The account is the user's own, or the token has no user.")
            })
    public RelationshipResponse block(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var blocker = viewerAccount(token);
        var target = activeAccount(id);
        if (target.id().equals(blocker.id())) {
            throw new InvalidRequestException("Validation failed: you can't block yourself");
        }
        accountBlocks.block(blocker, target);
        return relationship(blocker.id(), target.id());
    }

    /**
     * An account can be unblocked whatever its state, as it can be unfollowed.
     */
    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasAuthority('SCOPE_write:blocks')")
    @Operation(
            operationId = "unblockAccount",
            summary = "Unblock an account",
            description = """
                    Follows that the block removed don't come back. An account on another server is sent an `Undo`, \
                    unless the server is set not to send blocks. An account can be unblocked while it's suspended.\s\
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#unblock"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse unblock(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var blocker = viewerAccount(token);
        var target = RequestParameters.uuid(id).flatMap(actors::findById).orElseThrow(RecordNotFoundException::new);
        accountBlocks.unblock(blocker, target.id());
        return relationship(blocker.id(), target.id());
    }

    /**
     * Read from a form, the query, or a JSON body, since clients send each, and some send none, as they do for a boost.
     */
    @PostMapping("/{id}/mute")
    @PreAuthorize("hasAuthority('SCOPE_write:mutes')")
    @Operation(
            operationId = "muteAccount",
            summary = "Mute an account",
            description = """
                    Hides the account's statuses and boosts of them from the user's timelines and threads, and, \
                    unless `notifications` is false, its notifications. Follows are left as they are, and nothing is \
                    sent to other servers. Muting an account already muted changes the mute.

                    Differences from Mastodon: muting yourself, or a `duration` that's negative or not a whole \
                    number of seconds, gets a 422.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#mute"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is suspended."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The account is the user's own, a parameter is refused, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MuteBody.class)))
    public RelationshipResponse mute(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        var owner = viewerAccount(token);
        var target = activeAccount(id);
        if (target.id().equals(owner.id())) {
            throw new InvalidRequestException("Validation failed: you can't mute yourself");
        }
        var parameters = json.readAny(request, MAX_MUTE_BODY_BYTES);
        var notifications = parameters.get("notifications") == null || RequestParameters.flag(parameters, "notifications");
        var duration = muteDuration(parameters.get("duration"));
        mutes.mute(owner.id(), target.id(), notifications, duration.map(Instant.now()::plus));
        return relationship(owner.id(), target.id());
    }

    /**
     * Zero or nothing is a mute that doesn't end, as on Mastodon.
     */
    private static Optional<Duration> muteDuration(Object value) {
        long seconds;
        try {
            seconds = switch (value) {
                case null -> 0;
                case Integer number -> number;
                case Long number -> number;
                case String text when text.isEmpty() -> 0;
                case String text -> Long.parseLong(text);
                default -> -1;
            };
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        // Up to about a hundred years, which keeps the end within what PostgreSQL can store.
        if (seconds < 0 || seconds > 100L * 365 * 24 * 60 * 60) {
            throw new InvalidRequestException("Validation failed: duration must be a whole number of seconds");
        }
        return seconds == 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    }

    /**
     * An account can be unmuted whatever its state, as it can be unfollowed.
     */
    @PostMapping("/{id}/unmute")
    @PreAuthorize("hasAuthority('SCOPE_write:mutes')")
    @Operation(
            operationId = "unmuteAccount",
            summary = "Unmute an account",
            description = """
                    Notifications from while the account was muted don't come back. An account can be unmuted \
                    while it's suspended.\s""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#unmute"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse unmute(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var owner = viewerAccount(token);
        var target = RequestParameters.uuid(id).flatMap(actors::findById).orElseThrow(RecordNotFoundException::new);
        mutes.unmute(owner.id(), target.id());
        return relationship(owner.id(), target.id());
    }

    /**
     * Read from a form, the query, or a JSON body, as a mute is.
     */
    @PostMapping("/{id}/note")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "noteAccount",
            summary = "Set a private note on an account",
            description = """
                    Replaces the user's note about the account, which only the user sees, in `note` on the \
                    relationship. An empty or missing `comment` removes it. Nothing is sent to other servers. The user \
                    can write one about their own account, as on Mastodon.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#note"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is suspended."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The note is too long, isn't text, or has a NUL character or an unpaired surrogate, or the \
                            token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MemoBody.class)))
    public RelationshipResponse note(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        var owner = viewerAccount(token);
        var target = activeAccount(id);
        var comment = switch (json.readAny(request, MAX_MEMO_BODY_BYTES).get("comment")) {
            case null -> "";
            case String text -> text;
            default -> throw new InvalidRequestException("Validation failed: comment must be text");
        };
        // A blank one removes the memo whatever its length, as on Mastodon.
        if (!comment.isBlank() && !AccountMemos.isStorable(comment)) {
            throw new InvalidRequestException(
                    "Validation failed: comment can't contain NUL characters or unpaired surrogates");
        }
        if (!comment.isBlank() && AccountMemos.isTooLong(comment)) {
            throw new InvalidRequestException("Validation failed: comment is too long (maximum is "
                    + AccountMemos.MAX_LENGTH + " characters)");
        }
        memos.set(owner.id(), target.id(), comment);
        return relationship(owner.id(), target.id());
    }

    @PostMapping("/{id}/endorse")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "endorseAccount",
            summary = "Endorse an account",
            description = ENDORSE + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#endorse"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is suspended."),
                    @ApiResponse(responseCode = "422", description = """
                            The user doesn't follow the account, or the token has no user.""")
            })
    public RelationshipResponse endorse(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var owner = viewerAccount(token);
        var target = activeAccount(id);
        if (!endorsements.endorse(owner.id(), target.id())) {
            throw new InvalidRequestException(
                    "Validation failed: you must already be following the account to endorse it");
        }
        return relationship(owner.id(), target.id());
    }

    /**
     * Mastodon's name for {@link #endorse} before 4.4, which clients still use.
     */
    @PostMapping("/{id}/pin")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "pinAccount",
            summary = "Endorse an account, under its old name",
            description = "The same as `POST /api/v1/accounts/{id}/endorse`. " + ENDORSE + ApiDocs.NEEDS_USER,
            deprecated = true,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#pin"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the account is suspended."),
                    @ApiResponse(responseCode = "422", description = """
                            The user doesn't follow the account, or the token has no user.""")
            })
    public RelationshipResponse pin(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return endorse(token, id);
    }

    /**
     * An account can be unendorsed whatever its state, as it can be unfollowed.
     */
    @PostMapping("/{id}/unendorse")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "unendorseAccount",
            summary = "Stop endorsing an account",
            description = "An account can be unendorsed while it's suspended. " + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#unendorse"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse unendorse(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var owner = viewerAccount(token);
        var target = RequestParameters.uuid(id).flatMap(actors::findById).orElseThrow(RecordNotFoundException::new);
        endorsements.unendorse(owner.id(), target.id());
        return relationship(owner.id(), target.id());
    }

    /**
     * Mastodon's name for {@link #unendorse} before 4.4, which clients still use.
     */
    @PostMapping("/{id}/unpin")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "unpinAccount",
            summary = "Stop endorsing an account, under its old name",
            description = """
                    The same as `POST /api/v1/accounts/{id}/unendorse`. An account can be unendorsed while it's \
                    suspended.\s""" + ApiDocs.NEEDS_USER,
            deprecated = true,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#unpin"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The relationship with the account."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public RelationshipResponse unpin(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return unendorse(token, id);
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> notAllowed(FollowNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This action is not allowed"));
    }

    @GetMapping("/{id}/lists")
    @PreAuthorize("hasAuthority('SCOPE_read:lists')")
    @Operation(
            operationId = "getAccountLists",
            summary = "List the user's lists that have an account",
            description = "Oldest first. A suspended or deleted account gets a 404.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#lists"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The lists."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<ListResponse> lists(@AuthenticationPrincipal TokenPrincipal token,
                                    @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var account = activeAccount(id);
        return lists.containing(viewer, account.id()).stream().map(ListResponse::from).toList();
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
                            schema = @Schema(type = "integer", defaultValue = "" + PageLimits.ACCOUNTS))
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
        return follows(token, id, true, RequestParameters.accountPage(limit, maxId, sinceId, minId));
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
                            schema = @Schema(type = "integer", defaultValue = "" + PageLimits.ACCOUNTS))
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
        return follows(token, id, false, RequestParameters.accountPage(limit, maxId, sinceId, minId));
    }

    /**
     * An account that hides its collections gets an empty list for anyone but itself, as Mastodon does. A remote account's
     * lists only have the accounts on this server.
     */
    private ResponseEntity<List<AccountResponse>> follows(
            TokenPrincipal token, String id, boolean followers, IdPage page) {
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
     * Statuses can't be tagged yet, so that filter has nothing to return. An account has few enough pins that they're
     * never paged.
     */
    @GetMapping("/{id}/statuses")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getAccountStatuses",
            summary = "List an account's statuses",
            description = """
                    Differences from Mastodon: for an account on another server, only the statuses it has sent here \
                    are listed, and older ones aren't fetched. Its boosts of other servers' statuses are only stored \
                    while someone here follows it, so others aren't listed. Statuses can't be tagged, so `tagged` \
                    returns an empty list. `only_media` leaves out boosts.

                    With `pinned`, the pins are listed the most recently pinned first, with no `Link` header, since \
                    they all fit in one page. Only `limit` applies to them, and `max_id`, `since_id` and `min_id` \
                    return an empty list. An account on another server only has the pins it has sent here, or that \
                    its `featured` collection listed when it was last fetched.

                    Only statuses the viewer can see are listed. A suspended account gets a 404.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#statuses"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + PageLimits.MAX_STATUSES + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + PageLimits.STATUSES)),
                    @Parameter(name = "exclude_replies", description = "Leave out replies. " + ApiDocs.FLAG),
                    @Parameter(name = "exclude_reblogs", description = "Leave out boosts. " + ApiDocs.FLAG),
                    @Parameter(name = "only_media", description = "Only list statuses with media. " + ApiDocs.FLAG),
                    @Parameter(name = "pinned", description = "Only list pinned statuses. " + ApiDocs.FLAG),
                    @Parameter(name = "tagged", description = "Returns an empty list if not empty.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, newest first."),
                    @ApiResponse(responseCode = "404", description = "Not found.")
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
        var actorId = activeAccount(id).id();
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var withoutReplies = RequestParameters.flag(excludeReplies);
        if (RequestParameters.flag(pinned)) {
            var viewer = RequestParameters.viewer(token);
            var listed = page.maxId() != null || page.sinceId() != null || page.minId() != null
                    ? List.<Status>of()
                    : pins.findPinned(actorId, viewer).stream().limit(page.limit()).toList();
            return ResponseEntity.ok(responses.list(listed, viewer, Optional.of(PostFilter.Context.ACCOUNT)));
        }
        var nothingToReturn = tagged != null && !tagged.isEmpty();
        List<Status> found = nothingToReturn
                ? List.of()
                : statuses.findByAccount(actorId, RequestParameters.viewer(token), withoutReplies,
                RequestParameters.flag(excludeReblogs), RequestParameters.flag(onlyMedia), page);

        var filters = new LinkedHashMap<String, String>();
        if (withoutReplies) {
            filters.put("exclude_replies", "true");
        }
        if (RequestParameters.flag(excludeReblogs)) {
            filters.put("exclude_reblogs", "true");
        }
        if (RequestParameters.flag(onlyMedia)) {
            filters.put("only_media", "true");
        }
        return responses.page("/api/v1/accounts/" + actorId + "/statuses", filters, page, found,
                RequestParameters.viewer(token), Optional.of(PostFilter.Context.ACCOUNT));
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
            @Schema(description = "The name to show. Empty clears it.", maxLength = ProfileService.MAX_DISPLAY_NAME)
            String display_name,
            @Schema(description = "The bio, as plain text. Empty clears it.", maxLength = ProfileService.MAX_NOTE)
            String note,
            @Schema(description = """
                    All of the profile's fields, replacing the ones it has. A field whose value is only an HTTPS \
                    URL is verified if the page there links back to the profile with `rel="me"`.""")
            List<FieldBody> fields_attributes,
            @Schema(type = "string", format = "binary", description = "An image file, in a multipart form only.")
            Object avatar,
            @Schema(type = "string", format = "binary", description = "An image file, in a multipart form only.")
            Object header,
            @Schema(type = "string", format = "binary", description = """
                    Pleroma's profile background. An image file, in a multipart form only, or an empty string to \
                    remove it.""")
            Object pleroma_background_image,
            @Schema(description = """
                    Whether requests to follow the user wait for the user to approve them. Turning it off approves \
                    those still waiting.""")
            Boolean locked,
            @Schema(description = """
                    Whether anyone's search can find the user's public statuses. Without it, only those who wrote, \
                    favourited, bookmarked, or are mentioned in a status can find it.""")
            Boolean indexable,
            @Schema(description = """
                    Whether to hide who's in the user's followers and following from everyone else. The counts are \
                    still shown.""")
            Boolean hide_collections,
            @Schema(description = "Posting settings. Only `quote_policy` and `quote_others` can be changed.")
            SourceBody source) {
    }

    @Schema(name = "AccountCredentialsSource")
    private record SourceBody(
            @Schema(allowableValues = {"public", "followers", "nobody"}, description = """
                    Whose quotes of the user's new statuses are approved automatically when the client doesn't say.""")
            String quote_policy,
            @Schema(allowableValues = {"reject", "review"}, description = """
                    Not in Mastodon. Whether the quotes of the user's new statuses that aren't approved automatically \
                    are rejected, or wait for the user to review them, when the client doesn't say.""")
            String quote_others) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "AccountFollow")
    private record FollowBody(
            @Schema(description = "Whether the account's boosts reach the user's home timeline and lists.",
                    defaultValue = "true")
            Boolean reblogs,
            @Schema(name = "notify", description = "Whether the user is notified of the account's new statuses.",
                    defaultValue = "false")
            Boolean notifies,
            @Schema(description = """
                    The only languages, as ISO 639 codes, whose statuses by the account reach the user's home timeline \
                    and lists. Empty or null for every language. Statuses whose language isn't known always do.""")
            List<String> languages) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "AccountMute")
    private record MuteBody(
            @Schema(description = "Whether to hide the account's notifications too.", defaultValue = "true")
            Boolean notifications,
            @Schema(description = "How many seconds the mute lasts. 0 or none is until it's lifted.", defaultValue = "0")
            Long duration) {
    }

    /**
     * The schema keeps Mastodon's name for the entity.
     */
    @Schema(name = "AccountNote")
    private record MemoBody(
            @Schema(description = "The note, as plain text. Empty or none removes it.", maxLength = AccountMemos.MAX_LENGTH)
            String comment) {
    }

    @Schema(name = "AccountCredentialsField")
    private record FieldBody(
            @Schema(maxLength = ProfileService.MAX_FIELD_NAME) String name,
            @Schema(maxLength = ProfileService.MAX_FIELD_VALUE) String value) {
    }
}
