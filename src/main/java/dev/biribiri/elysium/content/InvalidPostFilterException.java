package dev.biribiri.elysium.content;

/**
 * Thrown when a filter breaks a validation rule. The message is safe to show to the client.
 */
public class InvalidPostFilterException extends RuntimeException {

    InvalidPostFilterException(String message) {
        super(message);
    }
}
