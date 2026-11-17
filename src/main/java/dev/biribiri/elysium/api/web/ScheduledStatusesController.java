package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.ScheduledStatus;
import dev.biribiri.elysium.content.ScheduledStatusService;
import dev.biribiri.elysium.content.StatusPage;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ScheduledStatusesController.PATH)
@Tag(name = "Scheduled statuses", description = "Statuses that the user has scheduled to be posted later.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "scheduled_statuses/"))
public class ScheduledStatusesController {

    static final String PATH = "/api/v1/scheduled_statuses";

    private static final long UPDATE_BODY_BYTES = 4 * 1024;

    private final ScheduledStatusService scheduled;
    private final InstanceProperties instance;
    private final JsonBodies json;

    ScheduledStatusesController(ScheduledStatusService scheduled, InstanceProperties instance, JsonBodies json) {
        this.scheduled = scheduled;
        this.instance = instance;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getScheduledStatuses",
            summary = "List the user's scheduled statuses",
            description = """
                    Pages are by when each was scheduled, not when it's due, as on Mastodon.

                    Differences from Mastodon: a status is posted within about a minute of its time, and is checked \
                    again then. One that can no longer be posted, because what it replies to or quotes has been \
                    deleted or it's no longer allowed to quote, is dropped, and its media becomes unattached uploads \
                    for an hour by default, so that it can be posted again. While the user is suspended, nothing is \
                    posted, and what came due is posted once the suspension ends. One that fails to post for another \
                    reason is tried again, and dropped after about an hour and a quarter.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "scheduled_statuses/#get"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The scheduled statuses, most recently scheduled first."),
                    @ApiResponse(responseCode = "422", description = "A cursor isn't a UUID, or the token has no user.")
            })
    public ResponseEntity<List<ScheduledStatusResponse>> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var author = requireUser(token);
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var found = scheduled.page(author, page);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH, Map.of(), page.limit(),
                found.stream().map(ScheduledStatus::id).toList());
        return response.body(found.stream().map(each -> ScheduledStatusResponse.from(each, instance)).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getScheduledStatus",
            summary = "View a scheduled status",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "scheduled_statuses/#get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The scheduled status."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the ID isn't a UUID, it isn't the user's, or it's been posted."""),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public ScheduledStatusResponse get(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var author = requireUser(token);
        return scheduled.find(scheduledId(id), author)
                .map(found -> ScheduledStatusResponse.from(found, instance))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PutMapping(path = "/{id}",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public ScheduledStatusResponse updateFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form) {
        return update(token, id, form.toSingleValueMap());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "updateScheduledStatus",
            summary = "Change when a scheduled status is posted",
            description = """
                    Only `scheduled_at` can be changed, as on Mastodon. It has the same limits as when the status was \
                    scheduled, though the status already counts toward the total.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "scheduled_statuses/#update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The scheduled status as it is now."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the ID isn't a UUID, it isn't the user's, or it's been posted."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            `scheduled_at` is missing, isn't a date and time, or is too soon, the day it's moved to has \
                            too many, or the token has no user.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UpdateBody.class)))
    public ScheduledStatusResponse updateFromJson(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        return update(token, id, json.read(request, UPDATE_BODY_BYTES));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "deleteScheduledStatus",
            summary = "Cancel a scheduled status",
            description = """
                    Differences from Mastodon: its media becomes unattached uploads for an hour by default, so that it \
                    can be posted another way, as a deleted status's does.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "scheduled_statuses/#cancel"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the ID isn't a UUID, it isn't the user's, or it's been posted."""),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> delete(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var author = requireUser(token);
        if (!scheduled.cancel(scheduledId(id), author)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    private ScheduledStatusResponse update(TokenPrincipal token, String id, Map<String, ?> parameters) {
        var author = requireUser(token);
        var scheduledId = scheduledId(id);
        var at = StatusesController.scheduledAt(parameters.get("scheduled_at"));
        if (at == null) {
            throw new InvalidRequestException("Validation failed: scheduled_at is required");
        }
        return scheduled.reschedule(scheduledId, author, at)
                .map(found -> ScheduledStatusResponse.from(found, instance))
                .orElseThrow(RecordNotFoundException::new);
    }

    private static UUID scheduledId(String id) {
        return RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
    }

    private static UUID requireUser(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "ScheduledStatusUpdate")
    private record UpdateBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time", description = """
                    When to post the status, as an ISO 8601 date and time at least 5 minutes ahead.""")
            String scheduled_at) {
    }
}
