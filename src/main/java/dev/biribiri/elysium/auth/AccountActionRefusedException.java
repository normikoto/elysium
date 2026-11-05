package dev.biribiri.elysium.auth;

/**
 * An account can't have this done to it. The message is written for the person who asked for it.
 */
public class AccountActionRefusedException extends RuntimeException {

    public AccountActionRefusedException(String message) {
        super(message);
    }
}
