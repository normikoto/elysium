package dev.biribiri.elysium.media;

/**
 * An upload that can't be used as an image. The message is written for the person who uploaded it.
 */
public class InvalidImageException extends RuntimeException {

    public InvalidImageException(String message) {
        super(message);
    }

    public InvalidImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
