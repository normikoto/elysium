package dev.biribiri.elysium.activitypub.signature;

import java.net.URI;
import java.util.Set;

/**
 * What every signature format agrees on, so that each signer makes requests that each scheme on this server accepts.
 */
final class SignedRequests {

    private static final Set<String> METHODS_WITHOUT_BODIES = Set.of("GET", "HEAD");

    private SignedRequests() {
    }

    /**
     * @return whether a signature has to cover a digest. Any method but these can have a body, so even an empty one
     *         counts: otherwise, a sender could leave out the digest and have someone else add a body.
     */
    static boolean needsDigest(String method, byte[] body) {
        return !METHODS_WITHOUT_BODIES.contains(method) || body.length > 0;
    }

    /**
     * @return the path and query, as they're sent in the request line
     */
    static String requestTarget(URI target) {
        var ascii = URI.create(target.toASCIIString());
        var path = ascii.getRawPath() == null || ascii.getRawPath().isEmpty() ? "/" : ascii.getRawPath();
        return ascii.getRawQuery() == null ? path : path + "?" + ascii.getRawQuery();
    }

    /**
     * Leaves out a default port, as HTTP clients do when writing the {@code Host} header.
     *
     * @throws IllegalArgumentException if the target doesn't have a host that can be signed
     */
    static String authority(URI target) {
        // URI has no host for names it can't parse, such as ones with underscores or that aren't ASCII.
        if (target.getScheme() == null || target.getHost() == null) {
            throw new IllegalArgumentException("Can't sign a request to a URL without a valid host: " + target);
        }
        return KeyId.authority(target.getScheme(), target.getHost(), target.getPort());
    }
}
