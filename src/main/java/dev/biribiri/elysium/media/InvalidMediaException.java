package dev.biribiri.elysium.media;

/**
 * An upload that can't be stored. The message is written for the person who uploaded it.
 */
public class InvalidMediaException extends RuntimeException {

    public InvalidMediaException(String message) {
        super(message);
    }

    public InvalidMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
