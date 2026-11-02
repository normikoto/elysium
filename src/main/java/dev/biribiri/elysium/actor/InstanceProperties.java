package dev.biribiri.elysium.actor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

/**
 * @param baseUrl the public URL of this server, without a path
 */
@ConfigurationProperties("elysium.instance")
public record InstanceProperties(URI baseUrl) {

    public InstanceProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException("elysium.instance.base-url is required");
        }
        var scheme = baseUrl.getScheme();
        var path = baseUrl.getRawPath();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || baseUrl.getHost() == null
                || !(path == null || path.isEmpty() || path.equals("/"))
                || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "elysium.instance.base-url must be an http(s) URL with no path, query, or fragment: " + baseUrl);
        }
    }

    /**
     * The host, plus the port if it isn't the scheme's default. This is the domain in {@code acct:user@domain}.
     */
    public String domain() {
        var host = baseUrl.getHost().toLowerCase(Locale.ROOT);
        var port = baseUrl.getPort();
        var defaultPort = "https".equalsIgnoreCase(baseUrl.getScheme()) ? 443 : 80;
        return port == -1 || port == defaultPort ? host : host + ":" + port;
    }

    /**
     * Resolves an absolute path against the base URL.
     */
    public URI resolve(String path) {
        return baseUrl.resolve(path);
    }
}
