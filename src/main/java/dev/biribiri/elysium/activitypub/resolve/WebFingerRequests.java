package dev.biribiri.elysium.activitypub.resolve;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Asks another server's WebFinger about an address. The answer comes from whichever server the address names, so callers
 * treat it as a hint and check whatever it points to.
 */
@Component
class WebFingerRequests {

    private static final Logger log = LoggerFactory.getLogger(WebFingerRequests.class);

    static final Pattern USERNAME = Pattern.compile("[^\\s@/?#%]{1,255}");
    static final Pattern DOMAIN = Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");
    private static final String JRD_ACCEPT = "application/jrd+json, application/json";

    private final FederationHttpClient http;
    private final JsonMapper json;

    WebFingerRequests(FederationHttpClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    static boolean isAddress(String username, String domain) {
        return USERNAME.matcher(username).matches() && DOMAIN.matcher(domain).matches();
    }

    /**
     * @return the descriptor, or empty if the server couldn't be reached or didn't give one
     */
    Optional<JsonNode> lookUp(String username, String domain) {
        var url = UriComponentsBuilder.fromUriString("https://" + domain)
                .path("/.well-known/webfinger")
                .queryParam("resource", "acct:" + username + "@" + domain)
                .encode()
                .build()
                .toUri();
        FederationHttpClient.Response response;
        try {
            response = http.get(url, JRD_ACCEPT, new HttpHeaders());
        } catch (IOException e) {
            log.debug("Couldn't look up {}@{}: {}", username, domain, e.getMessage());
            return Optional.empty();
        }
        if (response.status() != 200) {
            log.debug("Looking up {}@{} returned {}", username, domain, response.status());
            return Optional.empty();
        }
        try {
            return Optional.of(json.readTree(response.body()));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
