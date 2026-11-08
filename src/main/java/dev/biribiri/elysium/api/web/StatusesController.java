package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.NewStatus;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statuses")
@Tag(name = "Statuses", description = "Posting, viewing, and deleting statuses.",
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

    private final StatusService statuses;
    private final StatusResponses responses;
    private final JsonBodies json;

    StatusesController(StatusService statuses, StatusResponses responses, JsonBodies json) {
        this.statuses = statuses;
        this.responses = responses;
        this.json = json;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse createFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @RequestParam MultiValueMap<String, String> form) {
        rejectUnsupported(form);
        return create(token, form.toSingleValueMap());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "createStatus",
            summary = "Post a status",
            description = """
                    Differences from Mastodon:

                    - Media, polls, and scheduled statuses aren't supported, and `media_ids`, `poll`, or `scheduled_at` \
                    with a value gets a 422 rather than being ignored. Empty values are allowed.
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

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new status."),
                    @ApiResponse(responseCode = "404", description = "`in_reply_to_id` isn't a status the user can see."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The status is empty or too long, a value is unsupported or of the wrong type, or the token \
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
                .map(responses::one)
                .orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/{id}/context")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getStatusContext",
            summary = "View a status's thread",
            description = """
                    Works without a token for public and unlisted statuses. Lists up to 40 ancestors, and up to 60 \
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
                .flatMap(statusId -> statuses.findContext(statusId, RequestParameters.viewer(token)))
                .map(context -> new ContextResponse(
                        responses.list(context.ancestors()), responses.list(context.descendants())))
                .orElseThrow(RecordNotFoundException::new);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "deleteStatus",
            summary = "Delete a status",
            description = """
                    Only the author can delete a status. Other servers that were sent it are sent a `Delete`. \
                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "statuses/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = """
                            The deleted status, with its source `text`, so that a client can offer to redraft it."""),
                    @ApiResponse(responseCode = "404", description = "Not found, or the status isn't the user's."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public StatusResponse delete(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var author = requireUser(token);
        return RequestParameters.uuid(id)
                .flatMap(statusId -> statuses.delete(statusId, author))
                .map(responses::oneWithText)
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
                replyParent(RequestParameters.string(parameters, "in_reply_to_id")));
        return responses.one(statuses.create(author, draft));
    }

    private static UUID requireUser(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    private static UUID replyParent(String id) {
        return id == null ? null : RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
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
            var unsupported = name.equals("media_ids") || name.equals("media_ids[]") || name.equals("scheduled_at")
                    || name.equals("poll") || name.startsWith("poll[");
            if (unsupported && isPresent(value)) {
                throw new InvalidRequestException("Validation failed: " + name + " is not supported");
            }
        });
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
    @Schema(name = "NewStatus")
    private record NewStatusBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The text, as plain text. URLs and mentions are linked, but hashtags aren't yet. A mention of an \
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
            @Schema(description = "Not supported, and only accepted when empty.")
            List<String> media_ids,
            @Schema(description = "Not supported, and only accepted when empty.")
            Map<String, Object> poll,
            @Schema(description = "Not supported, and only accepted when empty.")
            String scheduled_at) {
    }
}
