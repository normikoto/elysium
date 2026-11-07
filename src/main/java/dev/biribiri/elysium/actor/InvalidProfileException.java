package dev.biribiri.elysium.actor;

/**
 * A profile change that can't be saved. The message names what's wrong, for whoever made the change.
 */
public class InvalidProfileException extends RuntimeException {

    public InvalidProfileException(String message) {
        super(message);
    }

    public InvalidProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
