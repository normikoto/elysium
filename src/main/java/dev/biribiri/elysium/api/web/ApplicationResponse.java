package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.auth.ClientApplication;
import dev.biribiri.elysium.auth.ClientRegistrations.NewClient;

import java.util.List;

/**
 * Mastodon's Application entity. The credentials are only included when an application is first registered, which makes
 * it a CredentialApplication.
 *
 * @param redirectUri the redirect URIs joined by newlines, which Mastodon has deprecated but older clients still read
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record ApplicationResponse(
        String id,
        String name,
        @JsonInclude(JsonInclude.Include.ALWAYS) String website,
        List<String> scopes,
        @JsonProperty("redirect_uri") String redirectUri,
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("client_secret") String clientSecret,
        @JsonProperty("client_secret_expires_at") Integer clientSecretExpiresAt
) {

    static ApplicationResponse from(ClientApplication application) {
        return new ApplicationResponse(
                application.id(),
                application.name(),
                application.website(),
                application.scopes(),
                String.join("\n", application.redirectUris()),
                application.redirectUris(),
                null,
                null,
                null);
    }

    static ApplicationResponse withCredentials(NewClient client) {
        var application = client.application();
        return new ApplicationResponse(
                application.id(),
                application.name(),
                application.website(),
                application.scopes(),
                String.join("\n", application.redirectUris()),
                application.redirectUris(),
                application.clientId(),
                client.clientSecret(),
                0);
    }
}
