package dev.biribiri.elysium.content;

/**
 * A custom emoji can't be added or changed, for a reason an admin can be shown.
 */
public class InvalidCustomEmojiException extends RuntimeException {

    public InvalidCustomEmojiException(String message) {
        super(message);
    }
}
