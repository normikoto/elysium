package dev.biribiri.elysium.actor;

import java.net.URI;
import java.util.UUID;

/**
 * Where a profile's avatar or header is served.
 *
 * @param path       relative to the server's base URL, which is under {@link #PROXY_PATH} for an image on another server
 *                   that isn't kept here but can be, or null for one that's only linked to
 * @param staticPath the first frame, for clients set not to play animations. It's {@code path} for an image that isn't
 *                   animated.
 * @param remoteUrl  where an account on another server has the image, or null for a local account's
 */
public record ProfileImage(String path, String staticPath, URI remoteUrl) {

    /**
     * Where an account on another server's avatar or header is served from while it isn't kept here. A request for it
     * downloads it.
     */
    public static final String PROXY_PATH = "/media_proxy/accounts/";

    public static final String AVATAR = "avatar";

    public static final String HEADER = "header";

    public static final String STATIC = "static";

    public static String proxyPathOf(UUID actorId, boolean header, boolean still) {
        return PROXY_PATH + actorId + "/" + (header ? HEADER : AVATAR) + (still ? "/" + STATIC : "");
    }

    /**
     * A local account's.
     */
    public ProfileImage(String path, String staticPath) {
        this(path, staticPath, null);
    }

    /**
     * Whether it's served through {@link #PROXY_PATH}, rather than from a copy kept here.
     */
    public boolean isProxied() {
        return path != null && path.startsWith(PROXY_PATH);
    }

    /**
     * The copy kept here or the proxy if there is one, and otherwise its server's, as for attachments that aren't kept.
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
