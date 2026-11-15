package dev.biribiri.elysium.graph;

/**
 * Thrown when a list, or a change to its members, breaks a validation rule. The message is safe to show to the client.
 */
public class InvalidAccountListException extends RuntimeException {

    InvalidAccountListException(String message) {
        super(message);
    }
}
