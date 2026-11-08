package dev.biribiri.elysium.actor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param mode    who can create an account at the sign-up page. Invites work whatever the mode.
 * @param enabled the setting that {@code mode} replaced, only kept so that a deployment that still sets it fails to start,
 *                rather than quietly closing its sign-ups
 */
@ConfigurationProperties("elysium.registration")
public record RegistrationProperties(
        @DefaultValue("closed") Mode mode,
        Boolean enabled) {

    public RegistrationProperties {
        if (enabled != null) {
            throw new IllegalArgumentException("elysium.registration.enabled has been replaced by elysium.registration.mode: "
                    + "set that to open, approval, or closed instead. Open and approval need an SMTP server.");
        }
    }

    /**
     * Whether someone without an invite can ask for an account, which means that the server says it's open for sign-ups.
     */
    public boolean acceptsUninvited() {
        return mode != Mode.CLOSED;
    }

    public boolean approvalRequired() {
        return mode == Mode.APPROVAL;
    }

    public enum Mode {
        /**
         * Anyone can sign up, once they've confirmed their email address.
         */
        OPEN,
        /**
         * Like {@link #OPEN}, but an admin has to approve each sign-up too.
         */
        APPROVAL,
        /**
         * Only people with an invite can sign up.
         */
        CLOSED
    }
}
