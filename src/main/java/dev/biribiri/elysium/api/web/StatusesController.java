package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BookmarkService;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.ContentType;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.OtherQuotes;
import dev.biribiri.elysium.content.Poll;
import dev.biribiri.elysium.content.PollChange;
import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.QuoteChange;
import dev.biribiri.elysium.content.QuotePolicy;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.RemoteThreadFetcher;
import dev.biribiri.elysium.content.ScheduledStatusService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusBoost;
import dev.biribiri.elysium.content.StatusEdit;
import dev.biribiri.elysium.content.StatusLike;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusProperties;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.ThreadMuteService;
import dev.biribiri.elysium.content.Visibility;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/statuses")
@Tag(name = "Statuses", description = "Posting, viewing, editing, and deleting statuses.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/"))
public class StatusesController {

    private static final Optional<PostFilter.Context> THREAD = Optional.of(PostFilter.Context.THREAD);

    private static final String INTERACTIONS = """
            Works without a token for public and unlisted statuses. A boost's ID stands for the status it boosts. \
            Pages are by when each was made, and the cursors are the IDs in the `Link` header, not account IDs, as on \
            Mastodon. Suspended and deleted accounts, and those on suspended servers, are left out. With a token, \
            so are accounts the user has blocked, muted, or blocked the server of, and those that block the user, as \
            on Mastodon.""";

    private static final String INTERACTION_LIMIT = "How many to return. Values outside 1 to "
            + PageLimits.MAX_ACCOUNTS + " are clamped to them.";

    /**
     * The most a character can take up in JSON, as an escaped surrogate pair like {@code \ud83d\ude00}.
     */
    private static final long MAX_BYTES_PER_CHARACTER = 12;

    /**
     * Room for the JSON syntax and the fields that don't count toward the length limit.
     */
    private static final long OTHER_FIELDS_BYTES = 64 * 1024;

    /**
     * Room for a reblog's one parameter, and anything else a client sends along with it.
     */
    private static final long REBLOG_BODY_BYTES = 4 * 1024;

    private final StatusService statuses;
    private final LikeService likes;
    private final BoostService boosts;
    private final StatusResponses responses;
    private final JsonBodies json;
    private final QuoteService quotes;
    private final StatusPinService pins;
    private final RemoteThreadFetcher threads;
    private final ScheduledStatusService scheduled;
    private final InstanceProperties instance;
    private final BookmarkService bookmarks;
    private final ThreadMuteService threadMutes;
    private final ActorService actors;
    private final AccountResponses accounts;

    StatusesController(StatusService statuses, StatusResponses responses, JsonBodies json, LikeService likes,
                       BoostService boosts, QuoteService quotes, StatusPinService pins, RemoteThreadFetcher threads,
                       ScheduledStatusService scheduled, InstanceProperties instance, BookmarkService bookmarks,
                       ThreadMuteService threadMutes,
                       ActorService actors, AccountResponses accounts) {
        this.actors = actors;
        this.accounts = accounts;
        this.bookmarks = bookmarks;
        this.threadMutes = threadMutes;
        this.scheduled = scheduled;
        this.instance = instance;
        this.quotes = quotes;
        this.pins = pins;
        this.threads = threads;
        this.statuses = statuses;
        this.likes = likes;
        this.boosts = boosts;
        this.responses = responses;
        this.json = json;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public Object createFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @RequestParam MultiValueMap<String, String> form) {
        rejectRepeatedVisibility(form);
        return create(token, withLists(form));
    }

    /**
     * A form repeats {@code media_ids[]} for each ID, and {@code poll[options][]} for each option, which a single value
     * for each name would lose. The poll's fields are gathered into one {@code poll}, as JSON has it, which is null if
     * they're all empty.
     */
    private static Map<String, Object> withLists(MultiValueMap<String, String> form) {
        var parameters = new HashMap<String, Object>(form.toSingleValueMap());
        parameters.keySet().removeIf(StatusesController::isPoll);
        var poll = new HashMap<String, Object>();
        var options = new ArrayList<String>();
        form.forEach((name, values) -> {
            if (name.equals("poll[options][]") || name.equals("poll[options]")) {
                options.addAll(values);
                poll.put("options", options);
            } else if (name.startsWith("poll[") && name.endsWith("]") && !values.isEmpty()) {
                poll.put(name.substring("poll[".length(), name.length() - 1), values.getFirst());
            }
        });
        if (poll.values().stream().anyMatch(StatusesController::isPresent)) {
            parameters.put("poll", poll);
        } else if (form.keySet().stream().anyMatch(StatusesController::isPoll)) {
            parameters.put("poll", null);
        }
        parameters.remove("media_ids[]");
        var ids = new ArrayList<String>();
        ids.addAll(form.getOrDefault("media_ids", List.of()));
        ids.addAll(form.getOrDefault("media_ids[]", List.of()));
        ids.removeIf(String::isEmpty);
        if (form.containsKey("media_ids") || form.containsKey("media_ids[]")) {
            parameters.put("media_ids", ids);
        }
        return parameters;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "createStatus",
            summary = "Post a status",
            description = """
                    Differences from Mastodon:

                    - `scheduled_at` has to be an ISO 8601 date and time with an offset, such as \
                    `2030-01-01T09:00:00Z`. It has to be more than a minute ahead, rather than Mastodon's 5 minutes, \
                    or the request gets a 422. One in the past posts the status now, as on Mastodon. A user can have up \
                    to\s""" + ScheduledStatusService.TOTAL_LIMIT + """
                     scheduled, and\s""" + ScheduledStatusService.DAILY_LIMIT + """
                     for one day in UTC, as on Mastodon, and at most\s""" + ScheduledStatusService.MAX_HELD_MEDIA + """
                     uploads held for them. The status is checked as if it were posted now, and again \
                    when it's posted, within about a minute of its time. See `GET /api/v1/scheduled_statuses` for what \
                    happens to one that can no longer be posted then.
                    - A poll can have 2 to\s""" + Poll.MAX_OPTIONS + """
                     options of up to\s""" + Poll.MAX_OPTION_CHARACTERS + """
                     characters each, which have to be different, and be open for\s""" + Poll.MIN_EXPIRATION + """
                     to\s""" + Poll.MAX_EXPIRATION + """
                     seconds, as on Mastodon. A status with a poll can't have media, and has to have text.
                    - `media_ids` can have up to\s""" + MediaAttachments.MAX_PER_STATUS + """
                     uploads that the user made and hasn't attached yet. As on Mastodon, a video or audio can't be \
                    attached with anything else, and a status with media can have no text.
                    - The length limit defaults to\s""" + StatusProperties.DEFAULT_MAX_CHARACTERS + """
                     characters rather than 500, and `GET /api/v1/instance` gives it. As in Mastodon, each URL \
                    counts as 23 characters, unless it's longer than 2048 characters, when it \
                    counts at its full length. URLs can save at most 4096 characters per status, and any after that \
                    also count at their full length. As in Mastodon, a mention counts without its domain, though the \
                    domains can save at most 4096 characters per status.
                    - A mention of an account on another server is looked up first, and counts toward the user's limit \
                    of 30 lookups a minute. A mention that can't be found, or one past that limit that isn't stored \
                    here, stays as text rather than failing the request.
                    - `visibility` defaults to `public` only when it's missing. An empty value gets a 422, as do a \
                    repeated value and `visibility[]`.
                    - `Idempotency-Key` is accepted but ignored, so a client that retries a request can create a \
                    duplicate status.
                    - `quoted_status_id` can be a status on a server that doesn't say who can quote its statuses, \
                    which is quoted as such servers quote each other, without asking. A status from before this \
                    server supported quotes can only be quoted by its author. A status that can't be quoted gets a \
                    422.
                    - `local_only`, as on glitch-soc, keeps the status on this server: it isn't sent to other servers, \
                    and only accounts logged in here can see it. A reply to or quote of a local-only status is \
                    local-only too, and a local-only status that quotes a status from another server gets a 422.
                    - `content_type`, as on glitch-soc, Pleroma, and Akkoma, is `text/plain`, the default, or \
                    `text/markdown`, and anything else gets a 422. Markdown keeps only the formatting that Mastodon \
                    shows: headings are bold paragraphs, images are links, rules are dropped, tables stay as text, and HTML is \
                    shown as text. A mention or hashtag in code or in a link's text isn't linked, and doesn't notify \
                    anyone or tag the status.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = """
                            The new status, or the scheduled status if `scheduled_at` was given.""",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(
                                    oneOf = {StatusResponse.class, ScheduledStatusResponse.class}))),
                    @ApiResponse(responseCode = "404", description = """
                            `in_reply_to_id` or `quoted_status_id` isn't a status the user can see."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The status is empty or too long, media can't be attached, a value is unsupported or of the \
                            wrong type, a local-only status quotes one from another server, `scheduled_at` is too \
                            soon or the user has scheduled too many, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NewStatusBody.class)))
    public Object createFromJson(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var body = json.read(request, statuses.maxStoredCharacters() * MAX_BYTES_PER_CHARACTER + OTHER_FIELDS_BYTES);
        rejectRepeatedVisibility(body);
        return create(token, body);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getStatus",
            summary = "View a status",
            description = """
                    Works without a token for public and unlisted statuses. Others are only found for a user who can \
                    see them. A status from a suspended account gets a 404.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public StatusResponse get(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return RequestParameters.uuid(id)
                .flatMap(statusId -> statuses.findVisible(statusId, RequestParameters.viewer(token)))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse editFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form) {
        var parameters = withLists(form);
        parameters.put("media_attributes", MediaAttributes.fromForm(form));
        return edit(token, id, parameters);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "editStatus",
            summary = "Edit a status",
            description = """
                    Only the author can edit a status, and a boost can't be edited. Other servers that were sent it are \
                    sent an `Update`. The text has the same limits as a new status, and its mentions are looked up \
                    again in the same way. An account it no longer mentions can still see it.

                    Differences from Mastodon:

                    - A missing `poll` keeps the poll the status has, and an empty or null one removes it. Changing \
                    its `options` or `multiple` makes it a new poll, as on Mastodon, which loses its votes and is open \
                    for `expires_in` from now. Otherwise only `hide_totals` can change, and the poll keeps its votes \
                    and when it closes.
                    - `media_ids` has every attachment the status is to have, in order, and can add the user's uploads \
                    that aren't attached yet. A missing `media_ids` keeps the attachments it has. `media_attributes[]` \
                    only changes `description` and `focus`, and a `thumbnail` gets a 422.
                    - Earlier versions in the status's history don't keep their attachments.
                    - A missing `sensitive` or `language` keeps what the status has, as Mastodon does, and so does an \
                    empty `language`, so a language can't be removed. `visibility` and `in_reply_to_id` are ignored.
                    - `quoted_status_id` changes what the status quotes, which Mastodon doesn't allow. A missing one, \
                    or the status it quotes already, keeps the quote and its approval, and a null or empty one removes \
                    it. Another status is quoted as it would be in a new status, and its quote is approved again. A \
                    status that isn't followers-only or direct can't quote a followers-only one, a local-only one \
                    can't quote one from another server, and one that isn't local-only can't quote a local-only one, \
                    since the status's visibility can't change.
                    - `content_type` is `text/plain` or `text/markdown`, as for a new status. A missing one keeps \
                    what the status was written in, and `GET /api/v1/statuses/:id/source` gives it.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#edit"),
            responses = {
                    @ApiResponse(responseCode = "200", description = """
                            The status as it is now, which is unchanged if the edit changes nothing."""),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the status isn't the user's, or it's a boost, or `quoted_status_id` isn't a \
                            status the user can see."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The status is empty or too long, media can't be attached, the status to quote can't be \
                            quoted by it, a value is unsupported or of the wrong type, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StatusEditBody.class)))
    public StatusResponse editFromJson(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        var body = json.read(request, statuses.maxStoredCharacters() * MAX_BYTES_PER_CHARACTER + OTHER_FIELDS_BYTES);
        return edit(token, id, body);
    }

    @GetMapping("/{id}/source")
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getStatusSource",
            summary = "View a status's source",
            description = """
                    Gives the text and content warning of one of the user's own statuses, for editing it. Unlike \
                    Mastodon's, it also has `content_type`, as on glitch-soc and Pleroma, which is `text/plain` or \
                    `text/markdown`.\s""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#source"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status's source."),
                    @ApiResponse(responseCode = "404", description = "Not found, or the status isn't the user's."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusSourceResponse source(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id)
                .flatMap(statusId -> statuses.findVisible(statusId, Optional.of(viewer)))
                .filter(found -> found.actorId().equals(viewer) && !found.isReblog())
                .map(StatusSourceResponse::from)
                .orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getStatusHistory",
            summary = "View a status's edit history",
            description = """
                    Works without a token for public and unlisted statuses, and others are only found for a user who \
                    can see them. Lists each version oldest first, ending with the current one, so a status that was \
                    never edited has one. A status from another server only has the versions that this server was \
                    sent, so it can lack some that its own server shows. Only the latest 50 versions are kept.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#history"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Each version of the status."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public List<StatusEditResponse> history(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = RequestParameters.viewer(token);
        return RequestParameters.uuid(id)
                .flatMap(statusId -> statuses.findVisible(statusId, viewer))
                .map(found -> responses.history(found, statuses.findHistory(found)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/{id}/favourited_by")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getStatusFavouritedBy",
            summary = "List the accounts that favourited a status",
            description = INTERACTIONS + " For a status from another server, only favourites from this server's "
                    + "accounts are known here.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#favourited_by"),
            parameters = @Parameter(name = "limit", description = INTERACTION_LIMIT,
                    schema = @Schema(type = "integer", defaultValue = "" + PageLimits.ACCOUNTS)),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts, most recent favourite first."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public ResponseEntity<List<AccountResponse>> favouritedBy(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var status = visibleOriginal(token, id);
        var page = RequestParameters.accountPage(limit, maxId, sinceId, minId);
        var found = likes.likers(status.id(), RequestParameters.viewer(token), page);
        return interactions("/favourited_by", status, page,
                found.stream().map(StatusLike::id).toList(), found.stream().map(StatusLike::actorId).toList());
    }

    @GetMapping("/{id}/reblogged_by")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getStatusRebloggedBy",
            summary = "List the accounts that boosted a status",
            description = INTERACTIONS + " Only public and unlisted boosts are listed, so an author's boost of their "
                    + "own followers-only status isn't. For a status from another server, only the boosts that "
                    + "reached this server are known here.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#reblogged_by"),
            parameters = @Parameter(name = "limit", description = INTERACTION_LIMIT,
                    schema = @Schema(type = "integer", defaultValue = "" + PageLimits.ACCOUNTS)),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The accounts, most recent boost first."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public ResponseEntity<List<AccountResponse>> rebloggedBy(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var status = visibleOriginal(token, id);
        var page = RequestParameters.accountPage(limit, maxId, sinceId, minId);
        var found = boosts.boosters(status.id(), RequestParameters.viewer(token), page);
        return interactions("/reblogged_by", status, page,
                found.stream().map(StatusBoost::id).toList(), found.stream().map(StatusBoost::actorId).toList());
    }

    /**
     * Resolves the status before the cursors are read, so that one the viewer can't see gets a 404 even when they
     * aren't valid.
     */
    private Status visibleOriginal(TokenPrincipal token, String id) {
        return RequestParameters.uuid(id).flatMap(boosts::original)
                .flatMap(statusId -> statuses.findVisible(statusId, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * @param cursors  the favourites' or boosts' IDs, newest first, which page links are made from
     * @param actorIds their accounts, in the same order. An account removed since the page was read is left out.
     */
    private ResponseEntity<List<AccountResponse>> interactions(
            String suffix, Status status, IdPage page, List<UUID> cursors, List<UUID> actorIds) {
        var found = actors.findByIds(actorIds).stream().collect(Collectors.toMap(Actor::id, Function.identity()));
        var listed = actorIds.stream().map(found::get).filter(Objects::nonNull).toList();
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, "/api/v1/statuses/" + status.id() + suffix, Map.of(), page.limit(), cursors);
        return response.body(accounts.build(listed));
    }

    @GetMapping("/{id}/context")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getStatusContext",
            summary = "View a status's thread",
            description = """
                    Works without a token for public and unlisted statuses. A boost's ID gives the thread of the \
                    status it boosts. Lists up to 40 ancestors, and up to 60 \
                    descendants at most 20 replies deep, as Mastodon does for a request without a token, whatever the \
                    token. Statuses the user can't see are left out after those limits apply, so fewer can be listed. \
                    Only statuses stored here are listed. For a request with a token, missing parents, and replies to \
                    a status from another server, are fetched from other servers in the background, at most every 15 \
                    minutes for each status, so a later request can list more. While that's running, the response has \
                    a `Mastodon-Async-Refresh` header, as on Mastodon 4.5, whose `id` can be checked with \
                    `GET /api/v1_alpha/async_refreshes/:id`. Its `result_count` counts parents as well as replies.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#context"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status's ancestors and descendants.",
                            headers = @Header(name = AsyncRefreshesController.HEADER, description = """
                                    Sent while the thread is being fetched from other servers, as \
                                    `id="<id>", retry=<seconds>, result_count=<count>`.""")),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public ResponseEntity<ContextResponse> context(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = RequestParameters.viewer(token);
        var statusId = RequestParameters.uuid(id).flatMap(boosts::original).orElseThrow(RecordNotFoundException::new);
        var context = statuses.findContext(statusId, viewer).orElseThrow(RecordNotFoundException::new);
        var response = ResponseEntity.ok();
        // Only for someone signed in, so that anonymous requests can't have this server fetch on demand.
        if (viewer.isPresent()) {
            threads.fetchSoon(statusId).ifPresent(refresh ->
                    response.header(AsyncRefreshesController.HEADER, AsyncRefreshesController.headerValue(refresh)));
        }
        return response.body(new ContextResponse(
                responses.list(context.ancestors(), viewer, THREAD),
                responses.list(context.descendants(), viewer, THREAD)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "deleteStatus",
            summary = "Delete a status",
            description = """
                    Only the author can delete a status. Other servers that were sent it are sent a `Delete`. \
                    Deleting one of the user's boosts unboosts it, as `POST /api/v1/statuses/:id/unreblog` does.

                    Differences from Mastodon: unless `delete_media` is true, the status's attachments can be posted \
                    again for an hour by default, which the server's admin sets, rather than a day. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = """
                            The deleted status, with its source `text`, so that a client can offer to redraft it."""),
                    @ApiResponse(responseCode = "404", description = "Not found, or the status isn't the user's."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse delete(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @Parameter(description = "Whether to delete its attachments now, rather than keep them to be posted again.")
            @RequestParam(name = "delete_media", required = false) String deleteMedia) {
        var author = requireUser(token);
        var statusId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        if (boosts.isOwnBoost(statusId, author)) {
            // Built first, since the boost is gone afterward.
            var response = statuses.findVisible(statusId, Optional.of(author))
                    .map(found -> responses.oneWithText(found, Optional.of(author)))
                    .orElseThrow(RecordNotFoundException::new);
            boosts.unreblog(statusId, author);
            return response;
        }
        // Found first, since a deleted status has none.
        var media = responses.attachmentsOf(statusId);
        return statuses.delete(statusId, author, RequestParameters.flag(deleteMedia))
                .map(found -> responses.deleted(found, RequestParameters.viewer(token),
                        RequestParameters.flag(deleteMedia) ? List.of() : media))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/reblog")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(operationId = "reblogStatus", summary = "Boost a status",
            description = """
                    Boosts a public or unlisted status the user can see, or one of their own followers-only statuses, \
                    whose boost is followers-only too. A boost's ID boosts the status it boosts. Boosting a status \
                    again returns the boost that's there, whatever `visibility` says. `visibility` can be sent as a \
                    form, a query parameter, or JSON. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#boost"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The boost, with the boosted status in `reblog`."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, or the status is direct or someone else's followers-only status."""),
                    @ApiResponse(responseCode = "422", description = """
                            `visibility` isn't one of `public`, `unlisted`, or `private`, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReblogBody.class)))
    public StatusResponse reblog(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        var viewer = requireUser(token);
        var visibility = reblogVisibility(request);
        return RequestParameters.uuid(id).flatMap(statusId -> boosts.reblog(statusId, viewer, visibility))
                .map(found -> responses.one(found, Optional.of(viewer)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/unreblog")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(operationId = "unreblogStatus", summary = "Undo a boost",
            description = """
                    Removes the user's boost of a status they can see. A boost's ID stands for the status it boosts. \
                    Repeats have no additional effect. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#unreblog"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status that was boosted."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse unreblog(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id).flatMap(statusId -> boosts.unreblog(statusId, viewer))
                .map(found -> responses.one(found, Optional.of(viewer)))
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * Read from a form, the query, or a JSON body, since clients send each, and most send no body at all. A visibility that
     * can't be read as one value is rejected, as it is for a new status, rather than boosting publicly.
     */
    private Visibility reblogVisibility(HttpServletRequest request) throws IOException {
        var visibility = switch (json.readAny(request, REBLOG_BODY_BYTES).get("visibility")) {
            case null -> Visibility.PUBLIC;
            case String text -> Visibility.fromValue(text);
            default -> throw new InvalidRequestException("Validation failed: visibility must be a single value");
        };
        if (visibility == Visibility.DIRECT) {
            throw new InvalidRequestException("Validation failed: a boost can't be direct");
        }
        return visibility;
    }

    @PostMapping("/{id}/favourite")
    @PreAuthorize("hasAuthority('SCOPE_write:favourites')")
    @Operation(operationId = "favouriteStatus", summary = "Favourite a status",
            description = "Favourites a visible local or stored remote status. A boost's ID favourites the status it "
                    + "boosts. Repeats have no additional effect. "
                    + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#favourite"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status with updated favourite state."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse favourite(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id).flatMap(boosts::original).flatMap(statusId -> likes.favourite(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/unfavourite")
    @PreAuthorize("hasAuthority('SCOPE_write:favourites')")
    @Operation(operationId = "unfavouriteStatus", summary = "Undo a favourite",
            description = "Removes a favourite of a visible status, which a boost's ID stands for. Repeats have no "
                    + "additional effect. "
                    + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#unfavourite"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status with updated favourite state."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse unfavourite(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id).flatMap(boosts::original).flatMap(statusId -> likes.unfavourite(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/bookmark")
    @PreAuthorize("hasAuthority('SCOPE_write:bookmarks')")
    @Operation(operationId = "bookmarkStatus", summary = "Bookmark a status",
            description = "Bookmarks a visible status, privately: neither its author nor other servers are told. A "
                    + "boost's ID bookmarks the status it boosts. Repeats have no additional effect. "
                    + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#bookmark"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, now bookmarked."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse bookmark(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id).flatMap(boosts::original).flatMap(statusId -> bookmarks.bookmark(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/unbookmark")
    @PreAuthorize("hasAuthority('SCOPE_write:bookmarks')")
    @Operation(operationId = "unbookmarkStatus", summary = "Remove a bookmark",
            description = "Removes a bookmark of a status, which a boost's ID stands for. Repeats, and statuses that "
                    + "aren't bookmarked, have no additional effect. The bookmark is removed even if the user can no "
                    + "longer see the status, though that answers `404`. " + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#unbookmark"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, no longer bookmarked."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse unbookmark(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        // The raw ID too, for a status whose author has since been suspended, which isn't found as a boost or not.
        return RequestParameters.uuid(id).map(raw -> boosts.original(raw).orElse(raw))
                .flatMap(statusId -> bookmarks.unbookmark(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/mute")
    @PreAuthorize("hasAuthority('SCOPE_write:mutes')")
    @Operation(operationId = "muteStatusThread", summary = "Mute a status's thread",
            description = "Stops new notifications about statuses in the thread of a visible status, which a boost's "
                    + "ID stands for. It covers every status in the thread, including parts of it that are fetched "
                    + "later. Direct statuses still reach conversations, and quotes waiting for review still ask for "
                    + "it. Nobody else is told. Repeats have no additional effect. " + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#mute"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, its thread now muted."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse mute(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id).flatMap(boosts::original).flatMap(statusId -> threadMutes.mute(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/unmute")
    @PreAuthorize("hasAuthority('SCOPE_write:mutes')")
    @Operation(operationId = "unmuteStatusThread", summary = "Unmute a status's thread",
            description = "Unmutes the thread of a status, which a boost's ID stands for. Notifications from while it "
                    + "was muted don't come back. Repeats, and threads that aren't muted, have no additional effect. "
                    + "The thread is unmuted even if the user can no longer see the status, though that answers `404`. "
                    + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#unmute"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, its thread no longer muted."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse unmute(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        // The raw ID too, for a status whose author has since been suspended, which isn't found as a boost or not.
        return RequestParameters.uuid(id).map(raw -> boosts.original(raw).orElse(raw))
                .flatMap(statusId -> threadMutes.unmute(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/pin")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(operationId = "pinStatus", summary = "Pin a status to the profile",
            description = "Pins one of the user's own statuses, which other servers are sent as an `Add`. At most "
                    + StatusPinService.MAX_PER_ACCOUNT + " can be pinned, as on Mastodon. Repeats have no additional "
                    + "effect. " + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#pin"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, now pinned."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = """
                            The token has no user, the status is someone else's, a boost, or direct, or the limit on pins \
                            has been reached.""")
            })
    public StatusResponse pin(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id).flatMap(statusId -> pins.pin(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/unpin")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(operationId = "unpinStatus", summary = "Unpin a status from the profile",
            description = "Unpins a status, which other servers are sent as a `Remove`. Repeats, and statuses that "
                    + "aren't pinned, have no additional effect. " + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#unpin"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, no longer pinned."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse unpin(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = requireUser(token);
        return RequestParameters.uuid(id).flatMap(statusId -> pins.unpin(statusId, viewer))
                .map(found -> responses.one(found, RequestParameters.viewer(token)))
                .orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/{id}/quotes")
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getStatusQuotes",
            summary = "View a status's quotes",
            description = """
                    Lists the accepted quotes of a status that the user can see, newest first. Only quotes stored here \
                    are listed. A boost's ID lists the quotes of the status it boosts. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#quotes"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The quotes."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user, or a cursor is invalid.")
            })
    public ResponseEntity<List<StatusResponse>> quotes(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = requireUser(token);
        var status = RequestParameters.uuid(id).flatMap(boosts::original)
                .flatMap(statusId -> statuses.findVisible(statusId, Optional.of(viewer)))
                .orElseThrow(RecordNotFoundException::new);
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        return responses.page("/api/v1/statuses/" + status.id() + "/quotes", Map.of(), page,
                quotes.findAccepted(status.id(), viewer, page), Optional.of(viewer), Optional.empty());
    }

    @PostMapping("/{id}/quotes/{quotingId}/revoke")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "revokeQuote",
            summary = "Revoke a quote of a status",
            description = """
                    Withdraws the user's approval of a quote of their status, which stops the quote showing it. The \
                    quote's server, and the user's followers' servers, are sent a `Delete` of the approval. A quote \
                    from a server that can't ask for approval is only hidden here. Repeats have no additional effect. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#revoke_quote"),
            parameters = @Parameter(name = "quotingId", description = "The quote's ID."),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The quote, whose `quote.state` is `revoked`."),
                    @ApiResponse(responseCode = "403", description = "The status isn't the user's."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, or the other status doesn't quote it."""),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse revokeQuote(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @PathVariable String quotingId) {
        var viewer = requireUser(token);
        var statusId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var quoting = RequestParameters.uuid(quotingId).orElseThrow(RecordNotFoundException::new);
        return switch (quotes.revoke(statusId, quoting, viewer)) {
            case NOT_FOUND -> throw new RecordNotFoundException();
            case NOT_OWNED -> throw new ForbiddenActionException();
            case REVOKED -> statuses.findStored(quoting)
                    .map(found -> responses.one(found, Optional.of(viewer)))
                    .orElseThrow(RecordNotFoundException::new);
        };
    }

    @PostMapping("/{id}/quote/request")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "requestQuoteApproval",
            summary = "Ask again for approval of a quote",
            description = """
                    Not in Mastodon. Asks again for approval of what one of the user's statuses quotes, when the quote \
                    is `pending`, `rejected`, or `revoked`. A quote of a status on this server is decided again by its \
                    author's policy, and may wait for them to review it. One of a status on another server gets a new \
                    request, so its `quote.state` is `pending` again. A quote can only be asked for once an hour. \
                    """ + ApiDocs.NEEDS_USER,
            // Mastodon has no such endpoint, so this links to where quotes are made.
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, with its `quote` as it is now."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the status isn't the user's, or it doesn't quote anything."""),
                    @ApiResponse(responseCode = "422", description = """
                            The quote is accepted already, was asked for less than an hour ago, or can't be approved \
                            any more, since the quoted status is gone or its author doesn't allow the user to quote it, \
                            or the token has no user.""")
            })
    public StatusResponse requestQuoteApproval(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var author = requireUser(token);
        var statusId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        return switch (quotes.askAgain(statusId, author)) {
            case NOT_FOUND -> throw new RecordNotFoundException();
            case ALREADY_ACCEPTED -> throw new InvalidRequestException("This quote is accepted already");
            case TOO_SOON -> throw new InvalidRequestException("This quote was asked for too recently");
            case NOT_ALLOWED -> throw new InvalidRequestException("This status can't be quoted");
            case ASKED -> statuses.findStored(statusId)
                    .map(found -> responses.one(found, Optional.of(author)))
                    .orElseThrow(RecordNotFoundException::new);
        };
    }

    @PutMapping(value = "/{id}/interaction_policy",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse interactionPolicyFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form) {
        return interactionPolicy(token, id, form.toSingleValueMap());
    }

    @PutMapping(value = "/{id}/interaction_policy", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "setStatusInteractionPolicy",
            summary = "Change how quotes of a status are approved",
            description = """
                    Sets `quote_approval_policy` on one of the user's own statuses, which other servers that were sent \
                    it are sent in an `Update`. It doesn't change the quotes approved already, nor those waiting for \
                    the user to review. A followers-only or direct status can only be quoted by its author, whatever \
                    this says. Differences from Mastodon: `quote_others` sets whether the quotes that aren't approved \
                    automatically are rejected or wait for the user to review. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#interaction_policy"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, with its `quote_approval`."),
                    @ApiResponse(responseCode = "404", description = "Not found, the status isn't the user's, or it's a boost."),
                    @ApiResponse(responseCode = "422", description = """
                            Both `quote_approval_policy` and `quote_others` are missing, `quote_approval_policy` isn't \
                            one of `public`, `followers`, or `nobody`, `quote_others` isn't one of `reject` or \
                            `review`, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = InteractionPolicyBody.class)))
    public StatusResponse interactionPolicyFromJson(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        return interactionPolicy(token, id, json.read(request, OTHER_FIELDS_BYTES));
    }

    private StatusResponse interactionPolicy(TokenPrincipal token, String id, Map<String, ?> parameters) {
        var author = requireUser(token);
        var statusId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var policy = quotePolicy(parameters);
        var others = quoteOthers(parameters);
        if (policy == null && others == null) {
            throw new InvalidRequestException("Validation failed: quote_approval_policy is required");
        }
        return quotes.setPolicy(statusId, author, policy, others)
                .map(found -> responses.one(found, Optional.of(author)))
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * @return the policy, or null if none was given
     */
    private static QuotePolicy quotePolicy(Map<String, ?> parameters) {
        return switch (parameters.get("quote_approval_policy")) {
            case null -> null;
            case String value when value.isEmpty() -> null;
            case String value -> QuotePolicy.fromValue(value).orElseThrow(() -> new InvalidRequestException(
                    "Validation failed: quote_approval_policy must be public, followers, or nobody"));
            default -> throw new InvalidRequestException("Validation failed: quote_approval_policy must be a string");
        };
    }

    /**
     * @return null if it wasn't given
     */
    private static OtherQuotes quoteOthers(Map<String, ?> parameters) {
        var value = RequestParameters.string(parameters, "quote_others");
        return value == null ? null : OtherQuotes.fromValue(value).orElseThrow(() -> new InvalidRequestException(
                "Validation failed: quote_others must be reject or review"));
    }

    /**
     * @return what the text is written in, or null if it wasn't given
     */
    private static ContentType contentType(Map<String, ?> parameters) {
        var value = RequestParameters.string(parameters, "content_type");
        return value == null ? null : ContentType.fromValue(value).orElseThrow(() -> new InvalidRequestException(
                "Validation failed: content_type must be text/plain or text/markdown"));
    }

    /**
     * Quoting a boost quotes the status it boosts, as on Mastodon.
     */
    private UUID quoted(String id) {
        return id == null || id.isEmpty() ? null : RequestParameters.uuid(id).flatMap(boosts::original)
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * @return the status, or the scheduled status if it's to be posted later
     */
    private Object create(TokenPrincipal token, Map<String, ?> parameters) {
        var author = requireUser(token);
        var scheduledAt = scheduledAt(parameters.get("scheduled_at"));
        var draft = new NewStatus(
                RequestParameters.string(parameters, "status"),
                RequestParameters.string(parameters, "spoiler_text"),
                RequestParameters.string(parameters, "language"),
                visibility(parameters),
                RequestParameters.flag(parameters, "sensitive"),
                replyParent(RequestParameters.string(parameters, "in_reply_to_id")),
                mediaIds(parameters.get("media_ids")),
                quoted(RequestParameters.string(parameters, "quoted_status_id")),
                quotePolicy(parameters),
                poll(parameters.get("poll")),
                RequestParameters.flag(parameters, "local_only"),
                contentType(parameters),
                quoteOthers(parameters));
        // A time that's passed posts the status now, as on Mastodon, so a client whose clock is a little ahead still posts.
        if (scheduledAt != null && scheduledAt.isAfter(Instant.now())) {
            return ScheduledStatusResponse.from(scheduled.schedule(author, draft, scheduledAt), instance);
        }
        return responses.one(statuses.create(author, draft), RequestParameters.viewer(token));
    }

    /**
     * @return null if it's missing or empty, which posts the status now
     */
    static Instant scheduledAt(Object value) {
        return switch (value) {
            case null -> null;
            case String string when string.isEmpty() -> null;
            case String string -> {
                try {
                    yield OffsetDateTime.parse(string).toInstant();
                } catch (DateTimeParseException e) {
                    throw invalidScheduledAt();
                }
            }
            default -> throw invalidScheduledAt();
        };
    }

    private static InvalidRequestException invalidScheduledAt() {
        return new InvalidRequestException("Validation failed: scheduled_at must be an ISO 8601 date and time");
    }

    private StatusResponse edit(TokenPrincipal token, String id, Map<String, ?> parameters) {
        var author = requireUser(token);
        var statusId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var draft = new StatusEdit(
                RequestParameters.string(parameters, "status"),
                RequestParameters.string(parameters, "spoiler_text"),
                RequestParameters.string(parameters, "language"),
                isPresent(parameters.get("sensitive")) ? RequestParameters.flag(parameters, "sensitive") : null,
                parameters.containsKey("media_ids") ? mediaIds(parameters.get("media_ids")) : null,
                MediaAttributes.read(parameters.get("media_attributes")),
                parameters.containsKey("quoted_status_id")
                        ? new QuoteChange(quoted(RequestParameters.string(parameters, "quoted_status_id"))) : null,
                quotePolicy(parameters),
                parameters.containsKey("poll") ? new PollChange(poll(parameters.get("poll"))) : null,
                contentType(parameters),
                quoteOthers(parameters));
        return statuses.edit(statusId, author, draft)
                .map(found -> responses.one(found, Optional.of(author)))
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * An ID that isn't one can't be an upload, so it's refused the same way as one that isn't the user's.
     */
    private static List<UUID> mediaIds(Object value) {
        List<?> values = switch (value) {
            case null -> List.of();
            case String string when string.isEmpty() -> List.of();
            case String string -> List.of(string);
            case List<?> list -> list;
            default -> throw new InvalidRequestException("Validation failed: media_ids must be a list of IDs");
        };
        var ids = new ArrayList<UUID>();
        for (var id : values) {
            if (!(id instanceof String string)) {
                throw new InvalidRequestException("Validation failed: media_ids must be a list of IDs");
            }
            if (!string.isEmpty()) {
                ids.add(RequestParameters.uuid(string).orElseThrow(() -> new InvalidRequestException(
                        "Validation failed: Media can't be found, or is already attached to a status")));
            }
        }
        return ids;
    }

    /**
     * @return null if it's missing or empty
     */
    private static NewPoll poll(Object value) {
        if (!isPresent(value)) {
            return null;
        }
        if (!(value instanceof Map<?, ?> fields)) {
            throw new InvalidRequestException("Validation failed: poll must be an object");
        }
        var poll = new HashMap<String, Object>();
        fields.forEach((name, field) -> poll.put(String.valueOf(name), field));
        var options = switch (poll.get("options")) {
            case null -> throw new InvalidRequestException("Validation failed: poll[options] is required");
            case List<?> list when list.stream().allMatch(String.class::isInstance) ->
                    list.stream().map(String.class::cast).toList();
            default -> throw new InvalidRequestException("Validation failed: poll[options] must be a list of strings");
        };
        long expiresIn = switch (poll.get("expires_in")) {
            case null -> throw new InvalidRequestException("Validation failed: poll[expires_in] is required");
            case Integer number -> number;
            case Long number -> number;
            case String string when !string.isEmpty() -> RequestParameters.number("poll[expires_in]", string, 0);
            default -> throw new InvalidRequestException("Validation failed: poll[expires_in] must be a number");
        };
        return new NewPoll(options, expiresIn, RequestParameters.flag(poll, "multiple"),
                RequestParameters.flag(poll, "hide_totals"));
    }

    private static UUID requireUser(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    /**
     * A reply to a boost is a reply to the status it boosts, as on Mastodon.
     */
    private UUID replyParent(String id) {
        return id == null ? null : RequestParameters.uuid(id).flatMap(boosts::original)
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * A visibility that can't be read as one value is rejected, because reading only part of it could make a status
     * public.
     */
    private static void rejectRepeatedVisibility(Map<String, ?> parameters) {
        parameters.forEach((name, value) -> {
            var repeated = name.equals("visibility") && value instanceof Collection<?> values && values.size() > 1;
            if (repeated || name.startsWith("visibility[")) {
                throw new InvalidRequestException("Validation failed: visibility must be a single value");
            }
        });
    }

    private static boolean isPoll(String name) {
        return name.equals("poll") || name.startsWith("poll[");
    }

    private static boolean isPresent(Object value) {
        return switch (value) {
            case null -> false;
            case String string -> !string.isEmpty();
            case Collection<?> values -> values.stream().anyMatch(StatusesController::isPresent);
            case Map<?, ?> values -> !values.isEmpty();
            default -> true;
        };
    }

    /**
     * Only a missing visibility means public. An empty one is rejected, so that a client's mistake can't publish a status
     * that was meant to be private.
     */
    private static Visibility visibility(Map<String, ?> parameters) {
        return switch (parameters.get("visibility")) {
            case null -> Visibility.PUBLIC;
            case String value -> Visibility.fromValue(value);
            default -> throw new InvalidRequestException("Validation failed: visibility must be a string");
        };
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "Reblog")
    private record ReblogBody(
            @Schema(allowableValues = {"public", "unlisted", "private"}, defaultValue = "public",
                    description = "Who can see the boost. A boost of a followers-only status is always followers-only.")
            String visibility) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "StatusEdit")
    private record StatusEditBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The new text, as `content_type` says it's written. Mentions are looked up again, as for a new \
                    status.""")
            String status,
            @Schema(description = "A content warning, shown in place of the text until it's opened. Missing means none.")
            String spoiler_text,
            @Schema(description = "An ISO 639 language code. Missing or empty keeps the status's language.")
            String language,
            @Schema(description = "Whether the content is sensitive. Missing keeps what the status has.")
            Boolean sensitive,
            @Schema(description = """
                    Every attachment the status is to have, in order, including new uploads. Missing keeps the ones it \
                    has.""")
            List<String> media_ids,
            @Schema(description = "Changes to the description and focus of the status's attachments.")
            List<MediaAttributes.Body> media_attributes,
            @Schema(description = """
                    The poll it's to have. Missing keeps the one it has, and null or empty removes it. Changing its \
                    options or `multiple` resets its votes.""")
            PollBody poll,
            @Schema(description = """
                    The status it's to quote, which Mastodon doesn't allow changing. Missing, or the status it quotes \
                    already, keeps the quote, and null or empty removes it.""")
            String quoted_status_id,
            @Schema(allowableValues = {"public", "followers", "nobody"}, description = """
                    Whose quotes of it are approved automatically. Missing keeps whose are. Ignored for a \
                    followers-only or direct status.""")
            String quote_approval_policy,
            @Schema(allowableValues = {"reject", "review"}, description = """
                    Not in Mastodon. Whether the quotes of it that aren't approved automatically are rejected, or wait \
                    for the user to review them. Missing keeps which.""")
            String quote_others,
            @Schema(allowableValues = {"text/plain", "text/markdown"}, description = """
                    What the text is written in. Missing keeps what the status was written in.""")
            String content_type) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewPoll")
    private record PollBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The answers, in order.")
            List<String> options,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "How many seconds it's open for.")
            Integer expires_in,
            @Schema(description = "Whether more than one answer can be chosen.", defaultValue = "false")
            Boolean multiple,
            @Schema(description = "Whether to hide the counts until it ends.", defaultValue = "false")
            Boolean hide_totals) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "InteractionPolicy")
    private record InteractionPolicyBody(
            @Schema(allowableValues = {"public", "followers", "nobody"}, description = """
                    Whose quotes of the status are approved automatically. Missing keeps whose are, if \
                    `quote_others` is given.""")
            String quote_approval_policy,
            @Schema(allowableValues = {"reject", "review"}, description = """
                    Not in Mastodon. Whether the quotes of the status that aren't approved automatically are \
                    rejected, or wait for the user to review them. Missing keeps which.""")
            String quote_others) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewStatus")
    private record NewStatusBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The text, as `content_type` says it's written. URLs, mentions, and hashtags are linked. A mention \
                    of an account that can't be found, or one past the limit on lookups of accounts on other servers, \
                    stays as text.""")
            String status,
            @Schema(description = "A content warning, shown in place of the text until it's opened.")
            String spoiler_text,
            @Schema(description = "An ISO 639 language code.")
            String language,
            @Schema(allowableValues = {"public", "unlisted", "private", "direct"}, defaultValue = "public",
                    description = """
                            Who can see the status. A direct status is only sent to the accounts it mentions. An \
                            empty value gets a 422.""")
            String visibility,
            @Schema(description = "Whether the content is sensitive.", defaultValue = "false")
            Boolean sensitive,
            @Schema(description = "The status this replies to, which the user has to be able to see.")
            String in_reply_to_id,
            @Schema(description = """
                    Uploads to attach, in order, which the user made and hasn't attached yet. A status with media can \
                    have no text.""")
            List<String> media_ids,
            @Schema(description = "A poll, which can't go with media.")
            PollBody poll,
            @Schema(description = """
                    When to post the status, as an ISO 8601 date and time more than a minute ahead. Missing, empty, \
                    or in the past posts it now. Otherwise the response is a ScheduledStatus.""", format = "date-time")
            String scheduled_at,
            @Schema(description = """
                    The status to quote, which the user has to be able to see, and be allowed to quote. Quoting a \
                    followers-only status, which only its author can, makes this one followers-only.""")
            String quoted_status_id,
            @Schema(allowableValues = {"public", "followers", "nobody"}, description = """
                    Whose quotes of this status are approved automatically. Missing means the user's default. A \
                    followers-only or direct status can only be quoted by its author.""")
            String quote_approval_policy,
            @Schema(allowableValues = {"reject", "review"}, description = """
                    Not in Mastodon. Whether the quotes of this status that aren't approved automatically are \
                    rejected, or wait for the user to review them. Missing means the user's \
                    `source[quote_others]`.""")
            String quote_others,
            @Schema(description = """
                    Whether the status stays on this server, unsent to other servers and only shown to accounts \
                    logged in here. A reply to or quote of a local-only status is local-only whatever this says.""",
                    defaultValue = "false")
            Boolean local_only,
            @Schema(allowableValues = {"text/plain", "text/markdown"}, defaultValue = "text/plain", description = """
                    What the text is written in. Markdown keeps only what Mastodon shows, and HTML in it is shown as \
                    text.""")
            String content_type) {
    }
}
