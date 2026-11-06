package dev.biribiri.elysium.actor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

/**
 * @param baseUrl the public URL of this server, without a path. It is normalized to a lowercase scheme and host with
 *                no default port, because actor URIs are derived from it and can't change after federating.
 */
@ConfigurationProperties("elysium.instance")
public record InstanceProperties(URI baseUrl) {

    public InstanceProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException(
                    "elysium.instance.base-url is required: set it to the public URL of this server");
        }
        var scheme = baseUrl.getScheme() == null ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
        var path = baseUrl.getRawPath();
        if (!(scheme.equals("http") || scheme.equals("https"))
                || baseUrl.getHost() == null
                || baseUrl.getRawUserInfo() != null
                || !(path == null || path.isEmpty() || path.equals("/"))
                || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null) {
            // The value isn't included because it may contain credentials.
            throw new IllegalArgumentException(
                    "elysium.instance.base-url must be an http(s) URL with no credentials, path, query, or fragment");
        }

        var host = baseUrl.getHost().toLowerCase(Locale.ROOT);
        var port = baseUrl.getPort();
        var defaultPort = scheme.equals("https") ? 443 : 80;
        var authority = port == -1 || port == defaultPort ? host : host + ":" + port;
        baseUrl = URI.create(scheme + "://" + authority);
    }

    /**
     * The host, plus the port if it isn't the scheme's default. This is the domain in {@code acct:user@domain}.
     */
    public String domain() {
        return baseUrl.getAuthority();
    }

    /**
     * What browsers send as the {@code Origin} of requests from this server's own pages.
     */
    public String origin() {
        return baseUrl.toString();
    }

    /**
     * The inbox that every actor here shares. It's built from the base URL rather than stored with each actor, so local
     * actors' {@link Actor#sharedInboxUri} is null. Other servers store it, so it can't change.
     */
    public URI sharedInboxUri() {
        return resolve("/inbox");
    }

    /**
     * Resolves an absolute path against the base URL.
     */
    public URI resolve(String path) {
        return baseUrl.resolve(path);
    }
}
