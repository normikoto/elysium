package dev.biribiri.elysium.media;

import java.util.Objects;

/**
 * An image's files in the media directory.
 *
 * @param name       the image as uploaded, which can be animated
 * @param staticName its first frame, for clients set not to play animations. For a still image this is {@code name}.
 */
public record StoredImage(String name, String staticName) {

    public StoredImage {
        Objects.requireNonNull(name);
        Objects.requireNonNull(staticName);
    }

    public boolean isAnimated() {
        return !name.equals(staticName);
    }
}
