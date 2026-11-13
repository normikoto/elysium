package dev.biribiri.elysium.content;

/**
 * Thrown when a vote breaks a rule of its poll. The message is safe to show to the client.
 */
public class InvalidVoteException extends RuntimeException {

    InvalidVoteException(String message) {
        super(message);
    }
}
