package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.inbox.InboxService;
import dev.biribiri.elysium.inbox.NotificationGroup;
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
@RequestMapping("/api/v2/notifications")
@Tag(name = "Grouped notifications", externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "grouped_notifications/"))
@ApiResponse(responseCode = "422", description = "The token has no user, a cursor, account_id, limit, or expand_accounts is invalid.")
public class GroupedNotificationsController {
    private final InboxService inbox;
    private final InboxResponses responses;
    private final InstanceProperties instance;

    GroupedNotificationsController(InboxService inbox, InboxResponses responses, InstanceProperties instance) {
        this.inbox = inbox;
        this.responses = responses;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getGroupedNotifications", summary = "List notification groups",
            description = "Follows, favourites, and reblogs are grouped; mentions are individual groups. Notification policies and requests are not implemented.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "grouped_notifications/#get-all"),
            parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "limit", description = "Page size, clamped to 1–80.", schema = @Schema(type = "integer", defaultValue = "40")),
                    @Parameter(in = ParameterIn.QUERY, name = "max_id", description = "Only notifications older than this UUID.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "since_id", description = "Only notifications newer than this UUID, starting from the newest.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "min_id", description = "Closest newer notifications; takes precedence over since_id.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "types[]", description = "Notification types to include.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "exclude_types[]", description = "Notification types to exclude; exclusions win.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "account_id", description = "Only notifications from this account UUID.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "include_filtered", description = "Accepted without effect; notification policies are not implemented.", schema = @Schema(type = "boolean", defaultValue = "false")),
                    @Parameter(in = ParameterIn.QUERY, name = "supported_types[]", description = "Accepted; generated notification types have no fallback representation.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "grouped_types[]", description = "Types the client supports grouping. Defaults to follow, favourite, and reblog.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "expand_accounts", description = "full or partial_avatars.", schema = @Schema(type = "string", defaultValue = "full"))
            },
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public ResponseEntity<GroupedNotificationsResponse> list(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        var viewer = NotificationRequests.viewer(token);
        var page = NotificationRequests.page(query);
        var partial = NotificationRequests.partial(query);
        var found = inbox.groups(viewer, NotificationRequests.filter(query), page, NotificationRequests.array(query, "grouped_types"));
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, "/api/v2/notifications", NotificationRequests.filters(query), page.limit(),
                found.stream().map(NotificationGroup::paginationId).toList());
        return response.body(responses.groups(viewer, found, partial));
    }

    @GetMapping("/unread_count")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getUnreadNotificationGroupCount", summary = "Count unread notification groups",
            description = "Uses the notifications read marker; individual and grouped counts can differ.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "grouped_notifications/#unread-count"),
            parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "limit", description = "Count cap, clamped to 1–1000.", schema = @Schema(type = "integer", defaultValue = "100")),
                    @Parameter(in = ParameterIn.QUERY, name = "types[]", description = "Notification types to include.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "exclude_types[]", description = "Notification types to exclude; exclusions win.", array = @ArraySchema(schema = @Schema(type = "string"))),
                    @Parameter(in = ParameterIn.QUERY, name = "account_id", description = "Only notifications from this account UUID.", schema = @Schema(type = "string")),
                    @Parameter(in = ParameterIn.QUERY, name = "grouped_types[]", description = "Types the client supports grouping. Defaults to follow, favourite, and reblog.", array = @ArraySchema(schema = @Schema(type = "string")))
            },
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public UnreadCountResponse unread(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return new UnreadCountResponse(inbox.unreadGroupCount(NotificationRequests.viewer(token),
                NotificationRequests.filter(query), NotificationRequests.countLimit(query), NotificationRequests.array(query, "grouped_types")));
    }

    @GetMapping("/{group_key}")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotificationGroup", summary = "View a notification group",
            description = "" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "grouped_notifications/#get"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response."),
                    @ApiResponse(responseCode = "404", description = "Not found or not owned by the user.")})
    public GroupedNotificationsResponse get(@AuthenticationPrincipal TokenPrincipal token, @PathVariable("group_key") String key) {
        var viewer = NotificationRequests.viewer(token);
        var group = inbox.group(viewer, key).orElseThrow(RecordNotFoundException::new);
        return responses.groups(viewer, List.of(group), false);
    }

    @GetMapping("/{group_key}/accounts")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotificationGroupAccounts", summary = "List accounts in a notification group",
            description = "" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "grouped_notifications/#accounts"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response."),
                    @ApiResponse(responseCode = "404", description = "Not found or not owned by the user.")})
    public List<AccountResponse> accounts(@AuthenticationPrincipal TokenPrincipal token, @PathVariable("group_key") String key) {
        var viewer = NotificationRequests.viewer(token);
        inbox.group(viewer, key).orElseThrow(RecordNotFoundException::new);
        return responses.accountList(inbox.groupAccounts(viewer, key));
    }

    @PostMapping("/{group_key}/dismiss")
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    @Operation(operationId = "dismissNotificationGroup", summary = "Dismiss a notification group",
            description = "" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "grouped_notifications/#dismiss"),
            responses = {@ApiResponse(responseCode = "200", description = "Successful response.")})
    public Map<String, Object> dismiss(@AuthenticationPrincipal TokenPrincipal token, @PathVariable("group_key") String key) {
        inbox.dismissGroup(NotificationRequests.viewer(token), key);
        return Map.of();
    }
}
