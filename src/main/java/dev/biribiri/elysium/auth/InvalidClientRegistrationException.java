package dev.biribiri.elysium.auth;

/**
 * A client registration request failed validation. The message is safe to show to the client.
 */
public class InvalidClientRegistrationException extends RuntimeException {

    InvalidClientRegistrationException(String message) {
        super(message);
    }
}
