package dev.biribiri.elysium.actor;

import java.net.URI;

/**
 * Where a profile's avatar or header is served.
 *
 * @param path       relative to the server's base URL, or null for an image on another server that isn't kept here
 * @param staticPath the first frame, for clients set not to play animations. It's {@code path} for an image that isn't
 *                   animated.
 * @param remoteUrl  where an account on another server has the image, or null for a local account's
 */
public record ProfileImage(String path, String staticPath, URI remoteUrl) {

    /**
     * A local account's.
     */
    public ProfileImage(String path, String staticPath) {
        this(path, staticPath, null);
    }

    /**
     * The copy kept here if there is one, and otherwise its server's, as for attachments that aren't kept.
     *
     * @param still whether to give the first frame
     */
    public URI url(InstanceProperties instance, boolean still) {
        if (path == null) {
            return remoteUrl;
        }
        return instance.resolve(still ? staticPath : path);
    }
}
