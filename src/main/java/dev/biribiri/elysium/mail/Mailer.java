package dev.biribiri.elysium.mail;

/**
 * Sends plain-text email.
 */
public interface Mailer {

    /**
     * Whether an SMTP server is configured. Without one, {@link #send} always fails.
     */
    boolean isConfigured();

    /**
     * Sends a message straight away. This waits for the SMTP server, so it mustn't be called inside a transaction.
     *
     * @throws MailNotSentException if the message couldn't be handed to the SMTP server
     */
    void send(String to, String subject, String body);
}
