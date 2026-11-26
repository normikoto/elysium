package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.notification.NotificationPolicy;
import dev.biribiri.elysium.notification.NotificationPolicy.Treatment;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/notifications/policy")
@Tag(name = "Notifications", externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/"))
public class NotificationPolicyController {
    private static final long MAX_BODY_BYTES = 4 * 1024;
    private static final String DIFFERENCES = """
            Differences from Mastodon: `for_limited_accounts` is kept but has no effect, since accounts and servers \
            can't be limited. A private mention that's filtered stays out of conversations until its request is \
            accepted.""";

    private final NotificationService notifications;
    private final JsonBodies json;

    NotificationPolicyController(NotificationService notifications, JsonBodies json) {
        this.notifications = notifications;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(operationId = "getNotificationPolicy", summary = "View the notification policy",
            description = DIFFERENCES + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#get-policy"),
            responses = {@ApiResponse(responseCode = "200", description = "The policy, with a summary of the requests waiting."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")})
    public NotificationPolicyResponse get(@AuthenticationPrincipal TokenPrincipal token) {
        var viewer = NotificationRequests.viewer(token);
        return NotificationPolicyResponse.from(notifications.policy(viewer), notifications.requestSummary(viewer));
    }

    @PatchMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    public NotificationPolicyResponse updateFromForm(@AuthenticationPrincipal TokenPrincipal token,
            @RequestParam MultiValueMap<String, String> form) {
        return update(token, form.toSingleValueMap());
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:notifications')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(operationId = "updateNotificationPolicy", summary = "Change the notification policy",
            description = "Each of `accept`, `filter`, or `drop`. What's left out stays as it is. " + DIFFERENCES
                    + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "notifications/#update-the-filtering-policy-for-notifications"),
            responses = {@ApiResponse(responseCode = "200", description = "The policy, with a summary of the requests waiting."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = "The token has no user, or a value isn't accept, filter, or drop.")})
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PolicyBody.class)))
    public NotificationPolicyResponse updateFromJson(@AuthenticationPrincipal TokenPrincipal token,
            HttpServletRequest request) throws IOException {
        return update(token, json.read(request, MAX_BODY_BYTES));
    }

    private NotificationPolicyResponse update(TokenPrincipal token, Map<String, ?> parameters) {
        var viewer = NotificationRequests.viewer(token);
        var change = new NotificationPolicy.Change(treatment(parameters, "for_not_following"),
                treatment(parameters, "for_not_followers"), treatment(parameters, "for_new_accounts"),
                treatment(parameters, "for_private_mentions"), treatment(parameters, "for_limited_accounts"),
                treatment(parameters, "for_bots"));
        return NotificationPolicyResponse.from(notifications.changePolicy(viewer, change), notifications.requestSummary(viewer));
    }

    private static Treatment treatment(Map<String, ?> parameters, String name) {
        var value = RequestParameters.string(parameters, name);
        if (value == null) return null;
        return Treatment.fromValue(value).orElseThrow(() ->
                new InvalidRequestException("Validation failed: " + name + " must be accept, filter, or drop"));
    }

    private record PolicyBody(
            @JsonProperty("for_not_following") @Schema(allowableValues = {"accept", "filter", "drop"}) String forNotFollowing,
            @JsonProperty("for_not_followers") @Schema(allowableValues = {"accept", "filter", "drop"}) String forNotFollowers,
            @JsonProperty("for_new_accounts") @Schema(allowableValues = {"accept", "filter", "drop"}) String forNewAccounts,
            @JsonProperty("for_private_mentions") @Schema(allowableValues = {"accept", "filter", "drop"}) String forPrivateMentions,
            @JsonProperty("for_limited_accounts") @Schema(allowableValues = {"accept", "filter", "drop"}) String forLimitedAccounts,
            @JsonProperty("for_bots") @Schema(allowableValues = {"accept", "filter", "drop"}) String forBots) {
    }
}
