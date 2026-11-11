package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.http.HttpServletRequest;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A login whose password was right, waiting for a code. It's kept in the session, which is stored by Java serialization, so
 * changing its fields leaves anyone partway through logging in during an upgrade to start again.
 *
 * @param loginGeneration the account's generation when the password was checked, so that ending its sessions meanwhile also
 *                        ends this: see {@link LoginGenerationFilter}
 */
record TwoFactorChallenge(UUID actorId, long loginGeneration, Instant expiresAt, int attemptsLeft) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String SESSION_ATTRIBUTE = "elysium.twoFactorChallenge";
    private static final Duration EXPIRY = Duration.ofMinutes(5);

    /**
     * Wrong codes before the password has to be typed again.
     */
    private static final int ATTEMPTS = 5;

    static void start(HttpServletRequest request, UUID actorId, long loginGeneration) {
        request.getSession().setAttribute(
                SESSION_ATTRIBUTE, new TwoFactorChallenge(actorId, loginGeneration, Instant.now().plus(EXPIRY), ATTEMPTS));
    }

    /**
     * @return the challenge in progress, unless it's expired
     */
    static Optional<TwoFactorChallenge> current(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof TwoFactorChallenge challenge)) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(challenge.expiresAt())) {
            session.removeAttribute(SESSION_ATTRIBUTE);
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    static void end(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }

    /**
     * Records a wrong code.
     *
     * @return whether there are attempts left
     */
    boolean failed(HttpServletRequest request) {
        if (attemptsLeft <= 1) {
            end(request);
            return false;
        }
        request.getSession().setAttribute(
                SESSION_ATTRIBUTE, new TwoFactorChallenge(actorId, loginGeneration, expiresAt, attemptsLeft - 1));
        return true;
    }
}
