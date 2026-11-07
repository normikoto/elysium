package dev.biribiri.elysium.actor;

/**
 * Where a profile's avatar or header is served.
 *
 * @param path       relative to the server's base URL
 * @param staticPath the first frame, for clients set not to play animations. It's {@code path} for an image that isn't
 *                   animated.
 */
public record ProfileImage(String path, String staticPath) {
}
