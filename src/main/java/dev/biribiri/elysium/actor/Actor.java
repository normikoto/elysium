package dev.biribiri.elysium.actor;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A local or remote account. The URI is its global identity.
 *
 * @param outboxUri    its outbox. This can be null for a remote actor, whose document doesn't have to include one.
 * @param publicKeyPem its public key. This can be null for a remote actor, whose document doesn't have to include one.
 * @param updatedAt    when this was last written, which for a remote actor says how old the copy is
 */
public record Actor(
        UUID id,
        URI uri,
        String username,
        String domain,
        URI inboxUri,
        URI outboxUri,
        URI sharedInboxUri,
        String publicKeyPem,
        Instant createdAt,
        Instant updatedAt
) {

    private static final Pattern VALID_LOCAL_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,30}");

    /**
     * The ID of this actor's public key, which other servers use to find the key that checks its signatures.
     */
    public String publicKeyId() {
        return uri + "#main-key";
    }

    public static boolean isValidLocalUsername(String username) {
        return VALID_LOCAL_USERNAME.matcher(username).matches();
    }
}
