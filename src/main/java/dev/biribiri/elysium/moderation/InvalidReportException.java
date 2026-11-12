package dev.biribiri.elysium.moderation;

/**
 * A report can't be saved as asked. The message is written to be shown to whoever filed it.
 */
public class InvalidReportException extends RuntimeException {

    InvalidReportException(String message) {
        super(message);
    }
}
