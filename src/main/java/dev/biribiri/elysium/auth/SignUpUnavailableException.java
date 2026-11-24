package dev.biribiri.elysium.auth;

/**
 * A sign-up failed because of the server, and can be tried again later. The message is safe to show to the client.
 */
public class SignUpUnavailableException extends RuntimeException {

    public SignUpUnavailableException(String message) {
        super(message);
    }
}
