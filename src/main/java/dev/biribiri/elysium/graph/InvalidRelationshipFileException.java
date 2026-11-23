package dev.biribiri.elysium.graph;

/**
 * Thrown when a file of accounts to import can't be read, or an import can't start. The message is shown to the person
 * importing.
 */
public class InvalidRelationshipFileException extends RuntimeException {

    public InvalidRelationshipFileException(String message) {
        super(message);
    }
}
