package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.notification.NotificationRequest;
import dev.biribiri.elysium.notification.NotificationService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Mastodon's notification requests, which hold a sender's filtered mentions and quotes.
 */
@RestController
@RequestMapping("/api/v1/notifications/requests")
@Tag(name = "Notifications", externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/"))
@ApiResponse(responseCode = "422", description = "The token has no user, or a cursor or limit is invalid.")
public class NotificationRequestsController {
    private final NotificationService notifications;
    private final NotificationResponses responses;
    private final InstanceProperties instance;

    NotificationRequestsController(NotificationService notifications, NotificationResponses responses,
                                   InstanceProperties instance) {
        this.notifications = notifications;
        this.responses = responses;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotificationRequests", summary = "List notification requests",
            description = """
                    One for each sender whose mentions or quotes the user's notification policy filtered, newest \
                    first. Pages default to 40 and allow up to 80 results.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#get-requests"),
            parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "limit", description = "Page size, clamped to 1–80.", schema = @Schema(type = "integer", defaultValue = "40")),
                    @Parameter(in = ParameterIn.QUERY, name = "max_id", description = "Only requests older than this UUID.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "since_id", description = "Only requests newer than this UUID, starting from the newest.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "min_id", description = "Closest newer requests; takes precedence over since_id.", schema = @Schema(type = "string"))
            },
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public ResponseEntity<List<NotificationRequestResponse>> list(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        var viewer = NotificationRequests.viewer(token);
        var page = NotificationRequests.requestPage(query);
        var found = notifications.requests(viewer, page);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, "/api/v1/notifications/requests", Map.of(), page.limit(),
                found.stream().map(NotificationRequest::id).toList());
        return response.body(responses.requests(viewer, found));
    }

    @GetMapping("/merged")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotificationRequestsMerged", summary = "Check whether accepted requests are merged",
            description = "Always true, since accepting a request merges its notifications straight away.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#requests-merged"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public Map<String, Boolean> merged(@AuthenticationPrincipal TokenPrincipal token) {
        NotificationRequests.viewer(token);
        return Map.of("merged", true);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotificationRequest", summary = "View a notification request",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#get-one-request"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)})
    public NotificationRequestResponse get(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = NotificationRequests.viewer(token);
        var request = notifications.request(viewer, NotificationRequests.id(id)).orElseThrow(RecordNotFoundException::new);
        return responses.requests(viewer, List.of(request)).stream().findFirst().orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "acceptNotificationRequest", summary = "Accept a notification request",
            description = """
                    Brings the sender's filtered notifications into the user's notifications, and its direct messages \
                    into conversations, without pushing them. The sender's notifications aren't filtered again.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#accept-request"),
            responses = {@ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)})
    public Map<String, Object> accept(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        if (!notifications.acceptRequest(NotificationRequests.viewer(token), NotificationRequests.id(id))) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "dismissNotificationRequest", summary = "Dismiss a notification request",
            description = """
                    Dismisses the sender's filtered notifications. Later ones are filtered again, and make a new \
                    request.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#dismiss-request"),
            responses = {@ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)})
    public Map<String, Object> dismiss(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        if (!notifications.dismissRequest(NotificationRequests.viewer(token), NotificationRequests.id(id))) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @PostMapping("/accept")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "acceptNotificationRequests", summary = "Accept several notification requests",
            description = "Unknown and malformed IDs are left out.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#accept-multiple-requests"),
            parameters = {
                    @Parameter(name = "id[]", description = "The requests' IDs, one value each.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(name = "id", description = "The same as `id[]`, for clients that leave out the brackets.", array = @ArraySchema(schema = @Schema(type = "string")))
            },
            responses = {@ApiResponse(responseCode = "200", description = "An empty object.")})
    public Map<String, Object> acceptAll(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        notifications.acceptRequests(NotificationRequests.viewer(token), NotificationRequests.ids(query));
        return Map.of();
    }

    @PostMapping("/dismiss")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "dismissNotificationRequests", summary = "Dismiss several notification requests",
            description = "Unknown and malformed IDs are left out.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#dismiss-multiple-requests"),
            parameters = {
                    @Parameter(name = "id[]", description = "The requests' IDs, one value each.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(name = "id", description = "The same as `id[]`, for clients that leave out the brackets.", array = @ArraySchema(schema = @Schema(type = "string")))
            },
            responses = {@ApiResponse(responseCode = "200", description = "An empty object.")})
    public Map<String, Object> dismissAll(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        notifications.dismissRequests(NotificationRequests.viewer(token), NotificationRequests.ids(query));
        return Map.of();
    }
}
