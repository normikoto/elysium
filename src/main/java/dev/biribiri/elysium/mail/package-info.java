/**
 * Sending email through the SMTP server that {@code spring.mail.*} configures. Only sign-ups send any so far.
 */
@ApplicationModule(allowedDependencies = {})
package dev.biribiri.elysium.mail;

import org.springframework.modulith.ApplicationModule;
