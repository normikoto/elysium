package dev.biribiri.elysium.content;

/**
 * Thrown when the status to quote doesn't exist, or the author can't see it.
 */
public class QuotedStatusNotFoundException extends RuntimeException {

    QuotedStatusNotFoundException() {
        super("The status to quote can't be found");
    }
}
