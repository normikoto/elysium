package dev.biribiri.elysium.announcement;

/**
 * An announcement can't be saved, for a reason that can be shown to the admin.
 */
public class InvalidAnnouncementException extends RuntimeException {

    public InvalidAnnouncementException(String message) {
        super(message);
    }
}
