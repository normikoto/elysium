package dev.biribiri.elysium.auth.internal;

import org.springframework.security.authentication.AuthenticationServiceException;

/**
 * Too many passwords are being hashed at once, and this one waited too long for its turn.
 */
public class PasswordHashingBusyException extends AuthenticationServiceException {

    public PasswordHashingBusyException() {
        super("Too many passwords are being hashed at once");
    }
}
