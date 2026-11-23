package dev.biribiri.elysium.actor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * @param baseUrl       the public URL of this server, without a path. It is normalized to a lowercase scheme and host
 *                      with no default port, because actor URIs are derived from it and can't change after federating.
 * @param accountDomain the domain in local users' addresses, {@code alice@accountDomain}. It defaults to the base URL's
 *                      authority, and can differ, as Mastodon's {@code LOCAL_DOMAIN} can, when that domain sends its
 *                      WebFinger requests here. Unlike the base URL it can change later, since actor URIs don't use it.
 */
@ConfigurationProperties("elysium.instance")
public record InstanceProperties(URI baseUrl, String accountDomain) {

    public InstanceProperties(URI baseUrl) {
        this(baseUrl, null);
    }

    @ConstructorBinding
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
        accountDomain = accountDomain == null || accountDomain.isBlank()
                ? authority
                : normalizeDomain(accountDomain.strip(), defaultPort);
    }

    /**
     * Normalized as the base URL's authority is, since other servers compare addresses as text: {@code example.com:443}
     * and {@code example.com} would otherwise be two different addresses for the same users.
     */
    private static String normalizeDomain(String domain, int defaultPort) {
        URI parsed;
        try {
            parsed = new URI("https://" + domain);
        } catch (URISyntaxException e) {
            parsed = null;
        }
        // URI accepts an empty port, as in example.com:, and a trailing dot, either of which would end up in every address.
        if (parsed == null || parsed.getHost() == null || parsed.getRawUserInfo() != null
                || !domain.equalsIgnoreCase(parsed.getRawAuthority())
                || domain.endsWith(":") || parsed.getHost().endsWith(".")) {
            throw new IllegalArgumentException(
                    "elysium.instance.account-domain must be a domain, with an optional port, and no scheme or path");
        }
        var host = parsed.getHost().toLowerCase(Locale.ROOT);
        var port = parsed.getPort();
        return port == -1 || port == defaultPort ? host : host + ":" + port;
    }

    /**
     * This server's host, plus the port if it isn't the scheme's default: what signatures and URIs are checked against.
     * Addresses use {@link #accountDomain} instead.
     */
    public String authority() {
        return baseUrl.getAuthority();
    }

    /**
     * Whether an address on this domain is a local user's. The server's own authority counts as well as the account
     * domain, because other servers first look up {@code acct:user@authority}, taken from an actor's URI.
     */
    public boolean isLocalDomain(String domain) {
        return domain != null && (domain.equalsIgnoreCase(accountDomain) || domain.equalsIgnoreCase(authority()));
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
