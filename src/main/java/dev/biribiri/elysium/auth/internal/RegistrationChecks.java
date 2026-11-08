package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.mail.Mailer;
import org.springframework.stereotype.Component;

/**
 * Stops the server from starting with sign-ups that nobody could finish, since each one waits for its email address to be
 * confirmed.
 */
@Component
class RegistrationChecks {

    RegistrationChecks(RegistrationProperties registration, Mailer mailer) {
        if (registration.acceptsUninvited() && !mailer.isConfigured()) {
            throw new IllegalStateException("elysium.registration.mode=" + registration.mode().name().toLowerCase()
                    + " needs an SMTP server to confirm email addresses: set spring.mail.host, or use "
                    + "elysium.registration.mode=closed and invites");
        }
    }
}
