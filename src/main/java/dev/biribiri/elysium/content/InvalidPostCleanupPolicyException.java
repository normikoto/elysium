package dev.biribiri.elysium.content;

/**
 * Thrown when a post cleanup policy breaks a validation rule. The message is safe to show to the user.
 */
public class InvalidPostCleanupPolicyException extends RuntimeException {

    InvalidPostCleanupPolicyException(String message) {
        super(message);
    }
}
