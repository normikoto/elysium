package dev.biribiri.elysium.actor;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

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

    private static final Pattern VALID_LOCAL_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,30}");

    public static boolean isValidLocalUsername(String username) {
        return VALID_LOCAL_USERNAME.matcher(username).matches();
    }
}
