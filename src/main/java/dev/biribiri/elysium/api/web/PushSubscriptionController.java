package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.push.InvalidPushSubscriptionException;
import dev.biribiri.elysium.push.PushAlerts;
import dev.biribiri.elysium.push.PushSubscriptions;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The token's push subscription. Each token has at most one, so the token is what names it.
 */
@RestController
@RequestMapping("/api/v1/push/subscription")
@Tag(name = "Push", description = "Web Push notifications.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "push/"))
public class PushSubscriptionController {

    private static final long MAX_BODY = 16 * 1024;

    /**
     * What Rails reads as false, which is how Mastodon reads these. Anything else is true.
     */
    private static final Set<String> FALSE = Set.of("", "0", "f", "false", "off");

    private final PushSubscriptions subscriptions;
    private final JsonBodies json;

    PushSubscriptionController(PushSubscriptions subscriptions, JsonBodies json) {
        this.subscriptions = subscriptions;
        this.json = json;
    }

    /**
     * @param alerts null if none were given
     * @param policy null if none was given
     */
    private record Input(String endpoint, String p256dh, String auth, boolean standard, Map<String, Boolean> alerts,
                         String policy) {
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_push')")
    public PushSubscriptionResponse subscribeForm(@AuthenticationPrincipal TokenPrincipal token,
                                                  @RequestParam MultiValueMap<String, String> form) {
        return subscribe(token, fromForm(form));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_push')")
    @Operation(operationId = "createPushSubscription", summary = "Subscribe to push notifications",
            description = """
                    Replaces any subscription the token has, with a new ID. Alert types that aren't given aren't \
                    pushed, and `policy` defaults to `all`. Messages are encrypted as `aes128gcm` when `standard` is \
                    true, and as the older `aesgcm` otherwise, as in Mastodon. The endpoint has to be an HTTPS URL on \
                    a public address.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "push/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new subscription."),
                    @ApiResponse(responseCode = "422", description = """
                            The token has no user, or the endpoint, a key, or the policy is missing or invalid.""")
            })
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = SubscribeBody.class)))
    public PushSubscriptionResponse subscribeJson(@AuthenticationPrincipal TokenPrincipal token,
                                                  HttpServletRequest request) throws IOException {
        return subscribe(token, fromJson(json.read(request, MAX_BODY)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_push')")
    @Operation(operationId = "getPushSubscription", summary = "Get the token's push subscription",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "push/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The subscription."),
                    @ApiResponse(responseCode = "404", description = "The token has no subscription."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public PushSubscriptionResponse get(@AuthenticationPrincipal TokenPrincipal token) {
        NotificationRequests.viewer(token);
        return subscriptions.find(token.authorizationId())
                .map(subscription -> PushSubscriptionResponse.from(subscription, subscriptions.serverKey()))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PutMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_push')")
    public PushSubscriptionResponse updateForm(@AuthenticationPrincipal TokenPrincipal token,
                                               @RequestParam MultiValueMap<String, String> form) {
        return update(token, fromForm(form));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_push')")
    @Operation(operationId = "updatePushSubscription", summary = "Change which notifications are pushed",
            description = """
                    Only `data` is read. If `alerts` is given, it replaces them all, so a type that's left out stops \
                    being pushed, as in Mastodon. If it isn't, they're kept, and so is the policy if that isn't given.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "push/#update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The subscription."),
                    @ApiResponse(responseCode = "404", description = "The token has no subscription."),
                    @ApiResponse(responseCode = "422", description = "The token has no user, or the policy is invalid.")
            })
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = UpdateBody.class)))
    public PushSubscriptionResponse updateJson(@AuthenticationPrincipal TokenPrincipal token,
                                               HttpServletRequest request) throws IOException {
        return update(token, fromJson(json.read(request, MAX_BODY)));
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('SCOPE_push')")
    @Operation(operationId = "deletePushSubscription", summary = "Unsubscribe from push notifications",
            description = "Succeeds whether or not the token has a subscription.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "push/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> delete(@AuthenticationPrincipal TokenPrincipal token) {
        NotificationRequests.viewer(token);
        subscriptions.remove(token.authorizationId());
        return Map.of();
    }

    private PushSubscriptionResponse subscribe(TokenPrincipal token, Input input) {
        var viewer = NotificationRequests.viewer(token);
        try {
            var subscription = subscriptions.subscribe(viewer, token.authorizationId(), input.endpoint(),
                    input.p256dh(), input.auth(), input.standard(), input.alerts(), input.policy());
            return PushSubscriptionResponse.from(subscription, subscriptions.serverKey());
        } catch (InvalidPushSubscriptionException e) {
            throw new InvalidRequestException("Validation failed: " + e.getMessage());
        }
    }

    private PushSubscriptionResponse update(TokenPrincipal token, Input input) {
        NotificationRequests.viewer(token);
        try {
            return subscriptions.update(token.authorizationId(), input.alerts(), input.policy())
                    .map(subscription -> PushSubscriptionResponse.from(subscription, subscriptions.serverKey()))
                    .orElseThrow(RecordNotFoundException::new);
        } catch (InvalidPushSubscriptionException e) {
            throw new InvalidRequestException("Validation failed: " + e.getMessage());
        }
    }

    /**
     * Rails' nested form keys, such as {@code data[alerts][mention]}.
     */
    private static Input fromForm(MultiValueMap<String, String> form) {
        Map<String, Boolean> alerts = null;
        for (var alert : PushAlerts.ALL) {
            var value = form.getFirst("data[alerts][" + alert + "]");
            if (value != null) {
                if (alerts == null) alerts = new HashMap<>();
                alerts.put(alert, isTrue(value));
            }
        }
        var standard = form.getFirst("subscription[standard]");
        return new Input(
                form.getFirst("subscription[endpoint]"),
                form.getFirst("subscription[keys][p256dh]"),
                form.getFirst("subscription[keys][auth]"),
                standard != null && isTrue(standard),
                alerts,
                form.getFirst("data[policy]"));
    }

    private static Input fromJson(Map<String, Object> body) {
        var subscription = object(body.get("subscription"), "subscription");
        var keys = object(subscription.get("keys"), "subscription[keys]");
        var data = object(body.get("data"), "data");
        Map<String, Boolean> alerts = null;
        if (data.get("alerts") != null) {
            alerts = new HashMap<>();
            for (var entry : object(data.get("alerts"), "data[alerts]").entrySet()) {
                alerts.put(entry.getKey(), isTrue(entry.getValue()));
            }
        }
        return new Input(
                string(subscription.get("endpoint")),
                string(keys.get("p256dh")),
                string(keys.get("auth")),
                isTrue(subscription.get("standard")),
                alerts,
                string(data.get("policy")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        return switch (value) {
            case null -> Map.of();
            case Map<?, ?> map -> (Map<String, Object>) map;
            default -> throw new InvalidRequestException("Validation failed: " + name + " must be an object");
        };
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isTrue(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean bool -> bool;
            default -> !FALSE.contains(value.toString().strip().toLowerCase(Locale.ROOT));
        };
    }

    private record SubscribeBody(Subscription subscription, Data data) {
    }

    private record UpdateBody(Data data) {
    }

    private record Subscription(
            @Schema(description = "The push service's URL for this client.") String endpoint,
            Keys keys,
            @Schema(description = "Whether the client reads RFC 8291's `aes128gcm`. Defaults to false.") Boolean standard) {
    }

    private record Keys(
            @Schema(description = "The client's P-256 public key, in base64url.") String p256dh,
            @Schema(description = "The client's 16-byte authentication secret, in base64url.") String auth) {
    }

    private record Data(
            @Schema(description = "Whether to push each type, such as `mention` or `pleroma:emoji_reaction`.")
            Map<String, Boolean> alerts,
            @Schema(allowableValues = {"all", "followed", "follower", "none"}) String policy) {
    }
}
