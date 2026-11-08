package dev.biribiri.elysium.mail;

public class MailNotSentException extends RuntimeException {

    public MailNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
