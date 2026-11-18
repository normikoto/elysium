package dev.biribiri.elysium.timeline;

public class MarkerConflictException extends RuntimeException {
    public MarkerConflictException() {
        super("Conflict during update, please try again");
    }
}
