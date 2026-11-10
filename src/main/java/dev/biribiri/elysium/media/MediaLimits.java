package dev.biribiri.elysium.media;

import org.springframework.util.unit.DataSize;

import java.util.Objects;

/**
 * The largest file of each kind that can be stored, which depends on what it's for.
 */
public record MediaLimits(DataSize image, DataSize video, DataSize audio) {

    public MediaLimits {
        Objects.requireNonNull(image);
        Objects.requireNonNull(video);
        Objects.requireNonNull(audio);
    }

    public DataSize of(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> image;
            case VIDEO -> video;
            case AUDIO -> audio;
        };
    }

    public DataSize largest() {
        return image.compareTo(video) >= 0 ? (image.compareTo(audio) >= 0 ? image : audio)
                : (video.compareTo(audio) >= 0 ? video : audio);
    }
}
