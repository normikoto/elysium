package dev.biribiri.elysium.push;

import dev.biribiri.elysium.push.internal.P256;
import dev.biribiri.elysium.push.internal.PushHttpClient;
import dev.biribiri.elysium.push.internal.PushSubscriptionRepository;
import dev.biribiri.elysium.push.internal.ServerKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Each access token's push subscription. A token has at most one, as in Mastodon, so it's found by the token's grant
 * rather than by its own ID.
 */
@Service
public class PushSubscriptions {

    static final int MAX_ENDPOINT_LENGTH = 2048;
    private static final int AUTH_SECRET_LENGTH = 16;

    private final PushSubscriptionRepository subscriptions;
    private final ServerKeys serverKeys;
    private final PushHttpClient client;

    PushSubscriptions(PushSubscriptionRepository subscriptions, ServerKeys serverKeys, PushHttpClient client) {
        this.subscriptions = subscriptions;
        this.serverKeys = serverKeys;
        this.client = client;
    }

    /**
     * Clients give this to the browser or push service when subscribing, which then only accepts messages that this
     * server signed.
     */
    public String serverKey() {
        return serverKeys.get().publicKeyBase64Url();
    }

    /**
     * Replaces any subscription the token has.
     *
     * @param p256dh the client's public key, in base64url
     * @param auth   the secret it shares with this server, in base64url
     * @param alerts whether to push each type. Types that aren't in {@link PushAlerts#ALL} are ignored, and missing ones
     *               aren't pushed.
     * @param policy null for {@link PushPolicy#ALL}
     * @throws InvalidPushSubscriptionException if anything is missing or malformed
     */
    // Not transactional, since checking the endpoint looks up its host, and the write is a single statement.
    public PushSubscription subscribe(UUID actorId, String authorizationId, String endpoint, String p256dh, String auth,
                                      boolean standard, Map<String, Boolean> alerts, String policy) {
        var uri = endpoint(endpoint);
        var key = decode(p256dh, "subscription[keys][p256dh]");
        try {
            P256.decodePublic(key);
        } catch (IllegalArgumentException e) {
            throw new InvalidPushSubscriptionException("subscription[keys][p256dh] isn't a P-256 public key");
        }
        var secret = decode(auth, "subscription[keys][auth]");
        if (secret.length != AUTH_SECRET_LENGTH) {
            throw new InvalidPushSubscriptionException("subscription[keys][auth] must be 16 bytes");
        }
        var enabled = enabled(alerts == null ? Map.of() : alerts);
        var chosen = policy == null ? PushPolicy.ALL : policy(policy);
        var id = UUID.randomUUID();
        subscriptions.replace(id, actorId, authorizationId, uri, key, secret, standard, enabled, chosen, Instant.now());
        return new PushSubscription(id, uri, standard, enabled, chosen);
    }

    public Optional<PushSubscription> find(String authorizationId) {
        return subscriptions.findByAuthorization(authorizationId).map(PushSubscriptionRepository.Stored::subscription);
    }

    /**
     * @param alerts null to keep them. Otherwise they're all replaced, so a type that's left out stops being pushed, as
     *               in Mastodon.
     * @param policy null to keep it
     * @return the subscription as it is now, or empty if the token has none
     */
    @Transactional
    public Optional<PushSubscription> update(String authorizationId, Map<String, Boolean> alerts, String policy) {
        var existing = find(authorizationId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        var enabled = alerts == null ? existing.get().alerts() : enabled(alerts);
        var chosen = policy == null ? existing.get().policy() : policy(policy);
        subscriptions.update(authorizationId, enabled, chosen);
        return find(authorizationId);
    }

    public void remove(String authorizationId) {
        subscriptions.removeForAuthorization(authorizationId);
    }

    private URI endpoint(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidPushSubscriptionException("subscription[endpoint] is required");
        }
        if (value.length() > MAX_ENDPOINT_LENGTH) {
            throw new InvalidPushSubscriptionException("subscription[endpoint] is too long");
        }
        try {
            var uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || !client.accepts(uri)) {
                throw new InvalidPushSubscriptionException("subscription[endpoint] must be an HTTPS URL");
            }
            if (client.isBlocked(uri)) {
                throw new InvalidPushSubscriptionException("subscription[endpoint] must be on a public address");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new InvalidPushSubscriptionException("subscription[endpoint] isn't a URL");
        }
    }

    /**
     * Browsers give keys in base64url without padding, but some clients pass them on as plain base64.
     */
    private static byte[] decode(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidPushSubscriptionException(name + " is required");
        }
        var normalized = value.strip().replace('+', '-').replace('/', '_').replace("=", "");
        try {
            return Base64.getUrlDecoder().decode(normalized);
        } catch (IllegalArgumentException e) {
            throw new InvalidPushSubscriptionException(name + " isn't base64url");
        }
    }

    private static Set<String> enabled(Map<String, Boolean> alerts) {
        var enabled = new LinkedHashSet<String>();
        for (var alert : PushAlerts.ALL) {
            if (Boolean.TRUE.equals(alerts.get(alert))) {
                enabled.add(alert);
            }
        }
        return enabled;
    }

    private static PushPolicy policy(String value) {
        return PushPolicy.fromValue(value)
                .orElseThrow(() -> new InvalidPushSubscriptionException("data[policy] must be all, followed, follower, or none"));
    }
}
