package dev.biribiri.elysium.media;

import java.util.Objects;

/**
 * A file in the media directory, as it's served.
 *
 * @param previewName a still image that stands for it, or null. An image always has one, and video and audio only when
 *                    a thumbnail was given with it
 * @param contentType what it's served as, worked out from its bytes
 * @param size        in bytes
 * @param width       null for video and audio, which aren't decoded
 */
public record StoredMedia(MediaKind kind, String name, String previewName, String contentType, long size, Integer width,
                          Integer height, Integer previewWidth, Integer previewHeight) {

    public StoredMedia {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(name);
        Objects.requireNonNull(contentType);
    }
}
