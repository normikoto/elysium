package dev.biribiri.elysium.moderation;

/**
 * A rule can't be saved as requested. The message is written to be shown to the admin.
 */
public class InvalidRuleException extends RuntimeException {

    InvalidRuleException(String message) {
        super(message);
    }
}
