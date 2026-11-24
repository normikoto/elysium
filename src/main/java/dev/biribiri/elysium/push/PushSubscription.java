package dev.biribiri.elysium.push;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * @param standard whether messages are encrypted as RFC 8291 says, rather than as the older draft that most clients still
 *                 expect
 * @param alerts   which of {@link PushAlerts#ALL} are pushed
 */
public record PushSubscription(UUID id, URI endpoint, boolean standard, Set<String> alerts, PushPolicy policy) {

    public PushSubscription {
        alerts = Set.copyOf(alerts);
    }
}
