package dev.biribiri.elysium.moderation;

/**
 * A block can't be saved as requested. The message is written to be shown to the admin.
 */
public class InvalidDomainBlockException extends RuntimeException {

    InvalidDomainBlockException(String message) {
        super(message);
    }
}
