package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.QuotePolicy;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.StatusEdit;
import dev.biribiri.elysium.content.StatusProperties;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statuses")
@Tag(name = "Statuses", description = "Posting, viewing, editing, and deleting statuses.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/"))
public class StatusesController {

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

    StatusesController(StatusService statuses, StatusResponses responses, JsonBodies json, LikeService likes,
                       BoostService boosts, QuoteService quotes) {
        this.quotes = quotes;
        this.statuses = statuses;
        this.likes = likes;
        this.boosts = boosts;
        this.responses = responses;
        this.json = json;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse createFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @RequestParam MultiValueMap<String, String> form) {
        rejectUnsupported(form);
        return create(token, withMediaIds(form));
    }

    /**
     * A form repeats {@code media_ids[]} for each ID, which a single value for each name would lose.
     */
    private static Map<String, Object> withMediaIds(MultiValueMap<String, String> form) {
        var parameters = new HashMap<String, Object>(form.toSingleValueMap());
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

                    - Polls and scheduled statuses aren't supported, and `poll` or `scheduled_at` with a value gets a \
                    422 rather than being ignored. Empty values are allowed.
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

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new status."),
                    @ApiResponse(responseCode = "404", description = """
                            `in_reply_to_id` or `quoted_status_id` isn't a status the user can see."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The status is empty or too long, media can't be attached, a value is unsupported or of the \
                            wrong type, or the token \
                            has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NewStatusBody.class)))
    public StatusResponse createFromJson(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var body = json.read(request, statuses.maxStoredCharacters() * MAX_BYTES_PER_CHARACTER + OTHER_FIELDS_BYTES);
        rejectUnsupported(body);
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
        rejectUnsupportedInEdit(form);
        var parameters = withMediaIds(form);
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

                    - Polls aren't supported, and `poll` with a value gets a 422 rather than being ignored. Empty values \
                    are allowed.
                    - `media_ids` has every attachment the status is to have, in order, and can add the user's uploads \
                    that aren't attached yet. A missing `media_ids` keeps the attachments it has. `media_attributes[]` \
                    only changes `description` and `focus`, and a `thumbnail` gets a 422.
                    - Earlier versions in the status's history don't keep their attachments.
                    - A missing `sensitive` or `language` keeps what the status has, as Mastodon does, and so does an \
                    empty `language`, so a language can't be removed. `visibility` and `in_reply_to_id` are ignored.
                    - `quoted_status_id` can only name the status it quotes already, and anything else gets a 422, \
                    since what a status quotes can't be changed.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#edit"),
            responses = {
                    @ApiResponse(responseCode = "200", description = """
                            The status as it is now, which is unchanged if the edit changes nothing."""),
                    @ApiResponse(responseCode = "404", description = "Not found, the status isn't the user's, or it's a boost."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The status is empty or too long, media can't be attached, a value is unsupported or of the \
                            wrong type, or the token \
                            has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StatusEditBody.class)))
    public StatusResponse editFromJson(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        var body = json.read(request, statuses.maxStoredCharacters() * MAX_BYTES_PER_CHARACTER + OTHER_FIELDS_BYTES);
        rejectUnsupportedInEdit(body);
        return edit(token, id, body);
    }

    @GetMapping("/{id}/source")
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getStatusSource",
            summary = "View a status's source",
            description = "Gives the text and content warning of one of the user's own statuses, for editing it. "
                    + ApiDocs.NEEDS_USER,
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
                    Only statuses stored here are listed: missing parents and replies aren't fetched from other \
                    servers.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#context"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status's ancestors and descendants."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public ContextResponse context(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return RequestParameters.uuid(id)
                .flatMap(boosts::original)
                .flatMap(statusId -> statuses.findContext(statusId, RequestParameters.viewer(token)))
                .map(context -> new ContextResponse(
                        responses.list(context.ancestors(), RequestParameters.viewer(token)),
                        responses.list(context.descendants(), RequestParameters.viewer(token))))
                .orElseThrow(RecordNotFoundException::new);
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
                quotes.findAccepted(status.id(), viewer, page), Optional.of(viewer));
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
            summary = "Change who can quote a status",
            description = """
                    Sets `quote_approval_policy` on one of the user's own statuses, which other servers that were sent \
                    it are sent in an `Update`. It doesn't change the quotes approved already. A followers-only or \
                    direct status can only be quoted by its author, whatever this says. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#interaction_policy"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, with its `quote_approval`."),
                    @ApiResponse(responseCode = "404", description = "Not found, the status isn't the user's, or it's a boost."),
                    @ApiResponse(responseCode = "422", description = """
                            `quote_approval_policy` is missing or isn't one of `public`, `followers`, or `nobody`, or the \
                            token has no user.""")
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
        if (policy == null) {
            throw new InvalidRequestException("Validation failed: quote_approval_policy is required");
        }
        return quotes.setPolicy(statusId, author, policy)
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
     * Quoting a boost quotes the status it boosts, as on Mastodon.
     */
    private UUID quoted(String id) {
        return id == null || id.isEmpty() ? null : RequestParameters.uuid(id).flatMap(boosts::original)
                .orElseThrow(RecordNotFoundException::new);
    }

    private StatusResponse create(TokenPrincipal token, Map<String, ?> parameters) {
        var author = requireUser(token);
        var draft = new NewStatus(
                RequestParameters.string(parameters, "status"),
                RequestParameters.string(parameters, "spoiler_text"),
                RequestParameters.string(parameters, "language"),
                visibility(parameters),
                RequestParameters.flag(parameters, "sensitive"),
                replyParent(RequestParameters.string(parameters, "in_reply_to_id")),
                mediaIds(parameters.get("media_ids")),
                quoted(RequestParameters.string(parameters, "quoted_status_id")),
                quotePolicy(parameters));
        return responses.one(statuses.create(author, draft), RequestParameters.viewer(token));
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
                quoted(RequestParameters.string(parameters, "quoted_status_id")),
                quotePolicy(parameters));
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
     * Ignoring these would do the wrong thing without telling anyone. For example, a scheduled status would be published
     * right away. Clients can still send them empty.
     *
     * <p>A visibility that can't be read as one value is also rejected here, because reading only part of it could make
     * a status public.
     */
    private static void rejectUnsupported(Map<String, ?> parameters) {
        parameters.forEach((name, value) -> {
            var repeated = name.equals("visibility") && value instanceof Collection<?> values && values.size() > 1;
            if (repeated || name.startsWith("visibility[")) {
                throw new InvalidRequestException("Validation failed: visibility must be a single value");
            }
            rejectIfPresent(name, value, isPoll(name) || name.equals("scheduled_at"));
        });
    }

    /**
     * As for a new status, ignoring these would leave out what the user meant to change without telling them.
     */
    private static void rejectUnsupportedInEdit(Map<String, ?> parameters) {
        parameters.forEach((name, value) -> rejectIfPresent(name, value, isPoll(name)));
    }

    private static boolean isPoll(String name) {
        return name.equals("poll") || name.startsWith("poll[");
    }

    private static void rejectIfPresent(String name, Object value, boolean unsupported) {
        if (unsupported && isPresent(value)) {
            throw new InvalidRequestException("Validation failed: " + name + " is not supported");
        }
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
                    The new text, as plain text. Mentions are looked up again, as for a new status.""")
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
            @Schema(description = "Not supported, and only accepted when empty.")
            Map<String, Object> poll,
            @Schema(description = "The status it quotes, which can't be changed. Missing keeps it.")
            String quoted_status_id,
            @Schema(allowableValues = {"public", "followers", "nobody"}, description = """
                    Who can quote it. Missing keeps who can. Ignored for a followers-only or direct status.""")
            String quote_approval_policy) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "InteractionPolicy")
    private record InteractionPolicyBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"public", "followers", "nobody"},
                    description = "Who can quote the status.")
            String quote_approval_policy) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewStatus")
    private record NewStatusBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The text, as plain text. URLs, mentions, and hashtags are linked. A mention of an \
                    account that can't be found, or one past the limit on lookups of accounts on other servers, stays \
                    as text.""")
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
            @Schema(description = "Not supported, and only accepted when empty.")
            Map<String, Object> poll,
            @Schema(description = "Not supported, and only accepted when empty.")
            String scheduled_at,
            @Schema(description = """
                    The status to quote, which the user has to be able to see, and be allowed to quote. Quoting a \
                    followers-only status, which only its author can, makes this one followers-only.""")
            String quoted_status_id,
            @Schema(allowableValues = {"public", "followers", "nobody"}, description = """
                    Who can quote this status. Missing means the user's default. A followers-only or direct status can \
                    only be quoted by its author.""")
            String quote_approval_policy) {
    }
}
