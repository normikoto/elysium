package dev.biribiri.elysium.auth;

/**
 * Nobody can sign up this way right now: sign-ups need an invite, or the server's first account has to come from the
 * command line.
 */
public class SignUpRefusedException extends RuntimeException {

    public SignUpRefusedException() {
        // Mastodon's message for registrations that are closed.
        super("This action is not allowed");
    }
}
