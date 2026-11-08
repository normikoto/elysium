package dev.biribiri.elysium.auth.internal;

/**
 * An invite code that doesn't exist, or has been revoked, has expired, or has no uses left.
 */
class InviteUnusableException extends RuntimeException {

    InviteUnusableException() {
        super("That invite doesn't work any more");
    }
}
