package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.InstanceProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * A key ID from a signature, which has been checked to be one this server is willing to look up.
 *
 * @param value     the ID as it was sent
 * @param document  the URL of the document that has the key, or for a local key, the URL of the actor
 * @param authority the host, plus the port if it isn't the scheme's default
 * @param local     whether this names a key on this server, which should never be fetched
 */
record KeyId(String value, URI document, String authority, boolean local) {

    private static final int MAX_LENGTH = 2048;

    /**
     * Checks a key ID without any network access. The ID comes straight from the request, so this is what stands between
     * anyone who can reach this server and the URLs it fetches.
     *
     * @throws SignatureVerificationException if this server won't look it up
     */
    static KeyId parse(String value, InstanceProperties instance) {
        if (value.length() > MAX_LENGTH) {
            throw rejected("Key ID is too long");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw rejected("Key ID isn't a valid URI");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost();
        // getHost is null for hosts Java can't parse, as well as for opaque IDs such as acct: URIs.
        if (!(scheme.equals("https") || scheme.equals("http"))
                || host == null
                || uri.getRawUserInfo() != null
                || host.endsWith(".")) {
            throw rejected("Key ID isn't one this server can look up");
        }

        var authority = authority(scheme, host, uri.getPort());
        if (authority.equals(instance.domain())) {
            // Built by joining rather than URI.resolve, which would treat a path like //other.example/ as a new host.
            var path = uri.getRawPath() == null ? "" : uri.getRawPath();
            var query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return new KeyId(value, URI.create(instance.baseUrl() + path + query), authority, true);
        }
        if (!scheme.equals("https")) {
            throw rejected("Key ID on another server has to use HTTPS");
        }
        var hash = value.indexOf('#');
        return new KeyId(value, URI.create(hash == -1 ? value : value.substring(0, hash)), authority, false);
    }

    /**
     * @return whether this ID has a fragment, which is how an ID usually points inside an actor document
     */
    boolean hasFragment() {
        return value.indexOf('#') != -1;
    }

    static String authority(String scheme, String host, int port) {
        var defaultPort = scheme.equalsIgnoreCase("https") ? 443 : 80;
        var lowerHost = host.toLowerCase(Locale.ROOT);
        return port == -1 || port == defaultPort ? lowerHost : lowerHost + ":" + port;
    }

    private static SignatureVerificationException rejected(String message) {
        return new SignatureVerificationException(Failure.REJECTED, message);
    }
}
