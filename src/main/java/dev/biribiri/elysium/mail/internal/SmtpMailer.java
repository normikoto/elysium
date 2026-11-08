package dev.biribiri.elysium.mail.internal;

import dev.biribiri.elysium.mail.MailNotSentException;
import dev.biribiri.elysium.mail.MailProperties;
import dev.biribiri.elysium.mail.Mailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Spring Boot only creates a {@link JavaMailSender} when {@code spring.mail.host} is set, so without one this server just
 * can't send email, and the features that need it refuse to start: see {@code RegistrationChecks}.
 */
@Service
class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    SmtpMailer(ObjectProvider<JavaMailSender> sender, MailProperties properties) {
        this.sender = sender.getIfAvailable();
        this.from = properties.from();
        if (this.sender != null && (from == null || from.isBlank())) {
            throw new IllegalArgumentException(
                    "elysium.mail.from is required once spring.mail.host is set: set it to the address email comes from");
        }
    }

    @Override
    public boolean isConfigured() {
        return sender != null;
    }

    @Override
    public void send(String to, String subject, String body) {
        if (sender == null) {
            throw new MailNotSentException("No SMTP server is configured: set spring.mail.host", null);
        }
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
        } catch (MailException e) {
            throw new MailNotSentException("Couldn't send email", e);
        }
    }
}
