package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.inbox.InboxService;
import dev.biribiri.elysium.inbox.Notification;
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

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/"))
@ApiResponse(responseCode = "422", description = "The token has no user, or a cursor, account_id, or limit is invalid.")
public class NotificationsController {
    private final InboxService inbox;
    private final InboxResponses responses;
    private final InstanceProperties instance;

    NotificationsController(InboxService inbox, InboxResponses responses, InstanceProperties instance) {
        this.inbox = inbox;
        this.responses = responses;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotifications", summary = "List notifications",
            description = "Only mention and follow events are currently generated. Notification policies and requests are not implemented. Pages default to 40 and allow up to 80 results.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#get-all"),
            parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "limit", description = "Page size, clamped to 1–80.", schema = @Schema(type = "integer", defaultValue = "40")),
                    @Parameter(in = ParameterIn.QUERY, name = "max_id", description = "Only notifications older than this UUID.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "since_id", description = "Only notifications newer than this UUID, starting from the newest.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "min_id", description = "Closest newer notifications; takes precedence over since_id.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "types[]", description = "Notification types to include.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "exclude_types[]", description = "Notification types to exclude; exclusions win.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "account_id", description = "Only notifications from this account UUID.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "include_filtered", description = "Accepted without effect; notification policies are not implemented.", schema = @Schema(type = "boolean", defaultValue = "false")),
                    @Parameter(in = ParameterIn.QUERY, name = "supported_types[]", description = "Accepted; generated notification types have no fallback representation.", array = @ArraySchema(schema = @Schema(type = "string")))
            },
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public ResponseEntity<List<NotificationResponse>> list(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        var viewer = NotificationRequests.viewer(token);
        var page = NotificationRequests.page(query);
        var found = inbox.notifications(viewer, NotificationRequests.filter(query), page);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, "/api/v1/notifications", NotificationRequests.filters(query), page.limit(),
                found.stream().map(Notification::id).toList());
        return response.body(responses.notifications(viewer, found));
    }

    @GetMapping("/unread_count")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getUnreadNotificationCount", summary = "Count unread notifications",
            description = "Counts notifications newer than the saved notifications marker; fetching notifications does not mark them read.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#unread-count"),
            parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "limit", description = "Count cap, clamped to 1–1000.", schema = @Schema(type = "integer", defaultValue = "100")),
                    @Parameter(in = ParameterIn.QUERY, name = "types[]", description = "Notification types to include.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "exclude_types[]", description = "Notification types to exclude; exclusions win.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "account_id", description = "Only notifications from this account UUID.", schema = @Schema(type = "string"))
            },
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public UnreadCountResponse unread(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return new UnreadCountResponse(inbox.unreadCount(NotificationRequests.viewer(token),
                NotificationRequests.filter(query), NotificationRequests.countLimit(query)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotification", summary = "View a notification",
            description = "" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#get"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response."),
                    @ApiResponse(responseCode = "404", description = "Not found or not owned by the user.")})
    public NotificationResponse get(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var viewer = NotificationRequests.viewer(token);
        var notification = inbox.notification(viewer, NotificationRequests.id(id)).orElseThrow(RecordNotFoundException::new);
        return responses.notifications(viewer, List.of(notification)).stream().findFirst().orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/clear")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "clearNotifications", summary = "Dismiss all notifications",
            description = "" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#clear"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public Map<String, Object> clear(@AuthenticationPrincipal TokenPrincipal token) {
        inbox.clear(NotificationRequests.viewer(token));
        return Map.of();
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "dismissNotification", summary = "Dismiss a notification",
            description = "" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#dismiss"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response."),
                    @ApiResponse(responseCode = "404", description = "Not found or not owned by the user.")})
    public Map<String, Object> dismiss(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        if (!inbox.dismiss(NotificationRequests.viewer(token), NotificationRequests.id(id))) throw new RecordNotFoundException();
        return Map.of();
    }
}
