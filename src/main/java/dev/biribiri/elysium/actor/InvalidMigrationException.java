package dev.biribiri.elysium.actor;

/**
 * An alias or a move that can't be saved. The message says why, for whoever asked for it.
 */
public class InvalidMigrationException extends RuntimeException {

    public InvalidMigrationException(String message) {
        super(message);
    }
}
