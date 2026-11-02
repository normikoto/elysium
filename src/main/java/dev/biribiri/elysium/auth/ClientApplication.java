package dev.biribiri.elysium.auth;

import java.util.List;

/**
 * A registered OAuth client.
 *
 * @param id           the application's ID, which isn't secret and isn't the OAuth client ID
 * @param name         the application's name
 * @param website      the application's homepage, or null
 * @param scopes       the scopes the application registered for
 * @param redirectUris the URIs the application can receive authorization responses at
 * @param clientId     the OAuth client ID
 */
public record ClientApplication(
        String id,
        String name,
        String website,
        List<String> scopes,
        List<String> redirectUris,
        String clientId
) {
}
