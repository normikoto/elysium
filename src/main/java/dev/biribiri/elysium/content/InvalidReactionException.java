package dev.biribiri.elysium.content;

/**
 * A reaction can't be added, for a reason that can be shown.
 */
public class InvalidReactionException extends RuntimeException {

    public InvalidReactionException(String message) {
        super(message);
    }
}
