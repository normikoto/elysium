package dev.biribiri.elysium.auth;

import dev.biribiri.elysium.auth.internal.AuthRateLimits;
import dev.biribiri.elysium.auth.internal.ClientSecretEncoder;
import dev.biribiri.elysium.auth.internal.OAuthScopes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Registers OAuth clients through Mastodon's app registration API, which Spring Authorization Server doesn't provide.
 */
@Service
public class ClientRegistrations {

    /**
     * Mastodon's tokens don't expire, but Spring needs a lifetime.
     */
    private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofDays(36_500);

    private static final String WEBSITE_SETTING = "elysium.client.website";
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_URI_LENGTH = 2048;
    private static final int MAX_REDIRECT_URIS = 10;
    private static final Set<String> FORBIDDEN_REDIRECT_SCHEMES = Set.of("javascript", "data", "vbscript", "file");

    private final RegisteredClientRepository clients;
    private final AuthRateLimits limits;
    private final StringKeyGenerator credentials =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    ClientRegistrations(RegisteredClientRepository clients, AuthRateLimits limits) {
        this.clients = clients;
        this.limits = limits;
    }

    /**
     * Registers a new client. Only valid registrations count against the request's limit, since the others don't store
     * anything.
     *
     * @param request the request asking for it, which the limit is counted for
     * @param scopes  space-separated scopes, defaulting to {@code read}
     * @param website the client's homepage, or null
     * @throws InvalidClientRegistrationException if the request isn't valid
     * @throws AppRegistrationLimitException       if the client has registered too many
     */
    public NewClient register(
            HttpServletRequest request, String name, List<String> redirectUris, String scopes, String website) {
        validateName(name);
        validateRedirectUris(redirectUris);
        validateWebsite(website);
        var parsedScopes = OAuthScopes.parse(scopes);
        validateScopes(parsedScopes);
        // The slot is taken before saving, so that concurrent registrations can't all get past the limit.
        var wait = limits.beginAppRegistration(request);
        if (wait.isPresent()) {
            throw new AppRegistrationLimitException(wait.get());
        }
        try {
            return save(name, redirectUris, parsedScopes, website);
        } catch (RuntimeException e) {
            limits.appRegistrationNotCounted(request);
            throw e;
        }
    }

    private NewClient save(String name, List<String> redirectUris, Set<String> parsedScopes, String website) {
        var clientSettings = ClientSettings.builder()
                .requireProofKey(false)
                .requireAuthorizationConsent(true);
        if (website != null && !website.isBlank()) {
            clientSettings.setting(WEBSITE_SETTING, website);
        }

        var secret = credentials.generateKey();
        var client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(credentials.generateKey())
                .clientSecret(ClientSecretEncoder.INSTANCE.encode(secret))
                .clientName(name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUris(uris -> uris.addAll(redirectUris))
                .scopes(granted -> granted.addAll(parsedScopes))
                .clientSettings(clientSettings.build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(ACCESS_TOKEN_LIFETIME)
                        .build())
                .build();
        clients.save(client);

        return new NewClient(toApplication(client, parsedScopes, redirectUris), secret);
    }

    public Optional<ClientApplication> findById(String id) {
        return Optional.ofNullable(clients.findById(id))
                .map(client -> toApplication(client, client.getScopes(), List.copyOf(client.getRedirectUris())));
    }

    /**
     * Finds an application by its OAuth client ID.
     */
    public Optional<ClientApplication> findByClientId(String clientId) {
        return Optional.ofNullable(clients.findByClientId(clientId))
                .map(client -> toApplication(client, client.getScopes(), List.copyOf(client.getRedirectUris())));
    }

    private static ClientApplication toApplication(
            RegisteredClient client, Set<String> scopes, List<String> redirectUris) {
        return new ClientApplication(
                client.getId(),
                client.getClientName(),
                client.getClientSettings().<String>getSetting(WEBSITE_SETTING),
                List.copyOf(scopes),
                redirectUris,
                client.getClientId());
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidClientRegistrationException("Name can't be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidClientRegistrationException("Name is too long");
        }
    }

    private static void validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new InvalidClientRegistrationException("Redirect URI can't be blank");
        }
        if (redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new InvalidClientRegistrationException("Too many redirect URIs");
        }
        for (var redirectUri : redirectUris) {
            // Spring stores redirect URIs as a comma-separated list, so a comma would split this into two.
            if (redirectUri.length() > MAX_URI_LENGTH || redirectUri.contains(",")) {
                throw new InvalidClientRegistrationException("Redirect URI must be an absolute URI.");
            }
            URI uri;
            try {
                uri = new URI(redirectUri);
            } catch (URISyntaxException e) {
                throw new InvalidClientRegistrationException("Redirect URI must be an absolute URI.");
            }
            if (!uri.isAbsolute() || uri.getRawFragment() != null) {
                throw new InvalidClientRegistrationException("Redirect URI must be an absolute URI.");
            }
            if (FORBIDDEN_REDIRECT_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new InvalidClientRegistrationException("Redirect URI uses a scheme that isn't allowed.");
            }
        }
    }

    private static void validateWebsite(String website) {
        if (website == null || website.isBlank()) {
            return;
        }
        try {
            var uri = new URI(website);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (website.length() <= MAX_URI_LENGTH && (scheme.equals("http") || scheme.equals("https"))) {
                return;
            }
        } catch (URISyntaxException e) {
            // Reported below.
        }
        throw new InvalidClientRegistrationException("Website must be an http or https URL.");
    }

    private static void validateScopes(Set<String> scopes) {
        if (!OAuthScopes.supported().containsAll(scopes)) {
            throw new InvalidClientRegistrationException("Scopes don't match the supported scopes.");
        }
    }

    /**
     * A client that was just registered. This is the only time its secret is available.
     */
    public record NewClient(ClientApplication application, String clientSecret) {
    }
}
