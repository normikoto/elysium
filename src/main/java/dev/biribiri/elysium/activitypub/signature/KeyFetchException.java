package dev.biribiri.elysium.activitypub.signature;

/**
 * A key couldn't be fetched.
 */
final class KeyFetchException extends Exception {

    private final boolean temporary;

    private KeyFetchException(String message, boolean temporary, Throwable cause) {
        super(message, cause);
        this.temporary = temporary;
    }

    /**
     * The other server might have the key later, such as when it's down or overloaded.
     */
    static KeyFetchException temporary(String message, Throwable cause) {
        return new KeyFetchException(message, true, cause);
    }

    /**
     * The other server gave an answer, and it wasn't a usable key.
     */
    static KeyFetchException permanent(String message, Throwable cause) {
        return new KeyFetchException(message, false, cause);
    }

    boolean isTemporary() {
        return temporary;
    }
}
