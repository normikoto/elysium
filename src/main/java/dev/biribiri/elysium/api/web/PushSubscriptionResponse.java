package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.push.PushAlerts;
import dev.biribiri.elysium.push.PushSubscription;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mastodon's WebPushSubscription entity.
 */
@Schema(name = "WebPushSubscription")
record PushSubscriptionResponse(
        @Schema(description = "A UUID, where Mastodon has a number.") String id,
        String endpoint,
        @Schema(description = "Whether messages are encrypted as RFC 8291's `aes128gcm`, rather than the older `aesgcm`.")
        boolean standard,
        @Schema(description = """
                Whether each type is pushed. As well as Mastodon's types, `pleroma:emoji_reaction` is for emoji \
                reactions.""")
        Map<String, Boolean> alerts,
        @Schema(allowableValues = {"all", "followed", "follower", "none"}) String policy,
        @JsonProperty("server_key") String serverKey
) {

    static PushSubscriptionResponse from(PushSubscription subscription, String serverKey) {
        var alerts = new LinkedHashMap<String, Boolean>();
        for (var alert : PushAlerts.ALL) {
            alerts.put(alert, subscription.alerts().contains(alert));
        }
        return new PushSubscriptionResponse(
                subscription.id().toString(),
                subscription.endpoint().toString(),
                subscription.standard(),
                alerts,
                subscription.policy().value(),
                serverKey);
    }
}
