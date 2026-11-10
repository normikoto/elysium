package dev.biribiri.elysium.content;

/**
 * An attachment that can't be uploaded or changed as asked. The message is written for the person who asked.
 */
public class InvalidMediaAttachmentException extends RuntimeException {

    public InvalidMediaAttachmentException(String message) {
        super(message);
    }
}
