package dev.biribiri.elysium.content;

/**
 * Thrown when a tag can't be featured. The message is safe to show to the client.
 */
public class InvalidFeaturedTagException extends RuntimeException {

    InvalidFeaturedTagException(String message) {
        super(message);
    }
}
