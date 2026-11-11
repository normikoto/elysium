package dev.biribiri.elysium.auth.internal;

import org.springframework.security.authentication.AccountStatusException;

import java.util.UUID;

/**
 * Thrown once a password has been checked, for an account that also needs a code, so that no login exists until the code has
 * been checked too. It's an {@link AccountStatusException} because Spring's {@code ProviderManager} stops at those, where it
 * would try another provider after any other failure.
 */
class TwoFactorRequiredException extends AccountStatusException {

    private final UUID actorId;

    TwoFactorRequiredException(UUID actorId) {
        super("A code is needed too");
        this.actorId = actorId;
    }

    UUID actorId() {
        return actorId;
    }
}
