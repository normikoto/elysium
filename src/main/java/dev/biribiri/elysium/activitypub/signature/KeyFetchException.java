package dev.biribiri.elysium.activitypub.signature;

/**
 * A key couldn't be fetched.
 */
final class KeyFetchException extends Exception {

    private final Kind kind;

    private KeyFetchException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * The other server might have the key later, such as when it's down or overloaded.
     */
    static KeyFetchException temporary(String message, Throwable cause) {
        return new KeyFetchException(message, Kind.TEMPORARY, cause);
    }

    /**
     * The other server gave an answer that isn't a usable key, but that doesn't say the key no longer exists. A firewall
     * or a maintenance page can answer like this too.
     */
    static KeyFetchException permanent(String message, Throwable cause) {
        return new KeyFetchException(message, Kind.PERMANENT, cause);
    }

    /**
     * The other server said this document doesn't exist.
     */
    static KeyFetchException gone(String message) {
        return new KeyFetchException(message, Kind.GONE, null);
    }

    boolean isTemporary() {
        return kind == Kind.TEMPORARY;
    }

    boolean isGone() {
        return kind == Kind.GONE;
    }

    private enum Kind {
        TEMPORARY, PERMANENT, GONE
    }
}
