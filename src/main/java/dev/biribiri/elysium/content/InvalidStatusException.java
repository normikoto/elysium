package dev.biribiri.elysium.content;

/**
 * Thrown when a new status breaks a validation rule. The message is safe to show to the client.
 */
public class InvalidStatusException extends RuntimeException {

    InvalidStatusException(String message) {
        super(message);
    }
}
