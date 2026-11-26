package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.notification.Notification;
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

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/"))
@ApiResponse(responseCode = "422", description = "The token has no user, or a cursor, account_id, or limit is invalid.")
public class NotificationsController {
    private final NotificationService notifications;
    private final NotificationResponses responses;
    private final InstanceProperties instance;

    NotificationsController(NotificationService notifications, NotificationResponses responses, InstanceProperties instance) {
        this.notifications = notifications;
        this.responses = responses;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotifications", summary = "List notifications",
            description = """
                    Elysium sends `mention`, `status`, `follow`, `follow_request`, `favourite`, `reblog`, `quote`, \
                    `quoted_update`, `update`, `poll`, `move`, `admin.report`, `pending.quote`, and Pleroma's \
                    `pleroma:emoji_reaction`. Only admins see `admin.report`.

                    With `notify` enabled on a follow, a `status` notification arrives when the followed account's \
                    post reaches the user's home timeline and is not a reply to someone else. Each local follower \
                    transferred by a move gets `move` with the new account in `target`. An emoji reaction has the \
                    emoji in `emoji` and, for a custom emoji, the image in `emoji_url`. A `poll` notification goes to \
                    the local author and local voters shortly after the poll ends.

                    A `quote` arrives after approval. Elysium adds `pending.quote` to ask the user to review a \
                    quote of their own status. Deciding removes `pending.quote` and, for approval, replaces it with \
                    `quote`. An `update` arrives for each edit to a boosted status and is removed if the user undoes \
                    their boost.

                    Resolving a follow request removes its notification. Approval adds a `follow` notification, \
                    unlike Mastodon. Notifications that the user's notification policy filters are left out unless \
                    `include_filtered` or `account_id` is given. Pages default to 40 and allow up to 80 results.

                    Pleroma's `pleroma.is_seen` is true for notifications at or before the notifications marker, \
                    which unread counts also use.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#get-all"),
            parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "limit", description = "Page size, clamped to 1–80.", schema = @Schema(type = "integer", defaultValue = "40")),
                    @Parameter(in = ParameterIn.QUERY, name = "max_id", description = "Only notifications older than this UUID.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "since_id", description = "Only notifications newer than this UUID, starting from the newest.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "min_id", description = "Closest newer notifications; takes precedence over since_id.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "types[]", description = "Notification types to include.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "exclude_types[]", description = "Notification types to exclude; exclusions win.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "account_id", description = "Only notifications from this account UUID, including those its notification policy filtered.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "include_filtered", description = "Whether to include notifications that the user's notification policy filtered.", schema = @Schema(type = "boolean", defaultValue = "false")),
                    @Parameter(in = ParameterIn.QUERY, name = "supported_types[]", description = "Accepted; generated notification types have no fallback representation.", array = @ArraySchema(schema = @Schema(type = "string")))
            },
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public ResponseEntity<List<NotificationResponse>> list(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        var viewer = NotificationRequests.viewer(token);
        var page = NotificationRequests.page(query);
        var found = notifications.notifications(viewer, NotificationRequests.filter(query, true), page);
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
        return new UnreadCountResponse(notifications.unreadCount(NotificationRequests.viewer(token),
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
        var notification = notifications.notification(viewer, NotificationRequests.id(id)).orElseThrow(RecordNotFoundException::new);
        return responses.notifications(viewer, List.of(notification)).stream().findFirst().orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/clear")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "clearNotifications", summary = "Dismiss all notifications",
            description = "" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#clear"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public Map<String, Object> clear(@AuthenticationPrincipal TokenPrincipal token) {
        notifications.clear(NotificationRequests.viewer(token));
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
        if (!notifications.dismiss(NotificationRequests.viewer(token), NotificationRequests.id(id))) throw new RecordNotFoundException();
        return Map.of();
    }
}
