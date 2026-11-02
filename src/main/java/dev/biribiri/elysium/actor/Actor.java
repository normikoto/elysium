package dev.biribiri.elysium.actor;

import java.net.URI;
import java.util.UUID;

/**
 * A local or remote account. The URI is its global identity.
 */
public record Actor(
        UUID id,
        URI uri,
        String username,
        String domain,
        URI inboxUri,
        URI outboxUri,
        URI sharedInboxUri,
        String publicKeyPem
) {
}
