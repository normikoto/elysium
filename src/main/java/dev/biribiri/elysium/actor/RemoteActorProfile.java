package dev.biribiri.elysium.actor;

import java.net.URI;

/**
 * What's stored about an actor on another server.
 *
 * @param domain         the host of {@code uri}, plus its port if it isn't the default
 * @param outboxUri      the actor's outbox, or null if it doesn't have one
 * @param sharedInboxUri the actor's shared inbox, or null if it doesn't have one
 * @param publicKeyPem   the actor's public key, or null if its document doesn't include one
 */
public record RemoteActorProfile(
        URI uri,
        String username,
        String domain,
        URI inboxUri,
        URI outboxUri,
        URI sharedInboxUri,
        String publicKeyPem
) {
}
