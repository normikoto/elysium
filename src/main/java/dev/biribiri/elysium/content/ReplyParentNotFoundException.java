package dev.biribiri.elysium.content;

/**
 * Thrown when a new status replies to a status that doesn't exist or that its author can't see.
 */
public class ReplyParentNotFoundException extends RuntimeException {

    ReplyParentNotFoundException() {
        super("The status being replied to was not found");
    }
}
