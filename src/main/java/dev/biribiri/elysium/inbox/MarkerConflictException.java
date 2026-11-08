package dev.biribiri.elysium.inbox;

public class MarkerConflictException extends RuntimeException {
    public MarkerConflictException() {
        super("Conflict during update, please try again");
    }
}
