package dev.biribiri.elysium.media;

/**
 * A file that isn't any of the formats that are accepted, rather than one that is but can't be used.
 */
public class UnsupportedMediaException extends InvalidMediaException {

    public UnsupportedMediaException(String message) {
        super(message);
    }
}
