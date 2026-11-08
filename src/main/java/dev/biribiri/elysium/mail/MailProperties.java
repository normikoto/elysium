package dev.biribiri.elysium.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The SMTP server itself is configured through Spring Boot's own {@code spring.mail.*} settings.
 *
 * @param from the address that email comes from, which is required once an SMTP server is configured
 */
@ConfigurationProperties("elysium.mail")
public record MailProperties(String from) {
}
