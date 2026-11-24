package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.notification.NotificationService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pleroma's way of marking notifications as seen, which Pleroma and Akkoma clients use instead of markers.
 */
@RestController
@Tag(name = "Notifications")
public class PleromaNotificationsController {
    private static final long MAX_BODY_BYTES = 4 * 1024;

    private final NotificationService notifications;
    private final NotificationResponses responses;
    private final JsonBodies json;

    PleromaNotificationsController(NotificationService notifications, NotificationResponses responses, JsonBodies json) {
        this.notifications = notifications;
        this.responses = responses;
        this.json = json;
    }

    @PostMapping("/api/v1/pleroma/notifications/read")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "markNotificationsAsSeen", summary = "Mark notifications as seen",
            description = """
                    Moves the notifications marker forward to `max_id`, so that it and everything older is seen and \
                    no longer counted as unread. A marker already at or past it stays where it is.

                    Differences from Pleroma: `id`, for marking one notification, isn't supported, since \
                    notifications are only seen up to a position. Any UUID is accepted, as with markers.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = "https://docs.akkoma.dev/stable/development/API/differences_in_mastoapi_responses/#notifications"),
            responses = {
                    @ApiResponse(responseCode = "200", description = """
                            The notifications this made seen, newest first, and at most 80. Empty if the marker \
                            didn't move."""),
                    @ApiResponse(responseCode = "413", description = "The body is larger than 4 KiB."),
                    @ApiResponse(responseCode = "422", description = """
                            `max_id` is missing or isn't a UUID, only `id` is given, or the token has no user.""")
            })
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReadBody.class)))
    public List<NotificationResponse> read(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var viewer = NotificationRequests.viewer(token);
        var parameters = json.readAny(request, MAX_BODY_BYTES);
        var maxId = parameters.get("max_id");
        if (maxId == null && parameters.get("id") != null) {
            throw new InvalidRequestException("Validation failed: id isn't supported, so use max_id");
        }
        if (maxId == null) throw new InvalidRequestException("Validation failed: max_id is required");
        var position = (maxId instanceof String string ? RequestParameters.uuid(string) : Optional.<UUID>empty())
                .orElseThrow(() -> new InvalidRequestException("Validation failed: max_id must be a UUID"));
        return responses.notifications(viewer, notifications.seeUpTo(viewer, position, PageLimits.MAX_NOTIFICATIONS));
    }

    private record ReadBody(@JsonProperty("max_id") String maxId) {
    }
}
