package dev.biribiri.elysium.activitypub.signature;

/**
 * A key or another document couldn't be fetched from a server.
 */
public final class DocumentFetchException extends Exception {

    private final Kind kind;

    private DocumentFetchException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * The other server might have the document later, such as when it's down or overloaded.
     */
    public static DocumentFetchException temporary(String message, Throwable cause) {
        return new DocumentFetchException(message, Kind.TEMPORARY, cause);
    }

    /**
     * The other server gave an answer that isn't a usable document, but that doesn't say the document no longer exists. A firewall
     * or a maintenance page can answer like this too.
     */
    public static DocumentFetchException permanent(String message, Throwable cause) {
        return new DocumentFetchException(message, Kind.PERMANENT, cause);
    }

    /**
     * The other server said this document doesn't exist.
     */
    public static DocumentFetchException gone(String message) {
        return new DocumentFetchException(message, Kind.GONE, null);
    }

    public boolean isTemporary() {
        return kind == Kind.TEMPORARY;
    }

    public boolean isGone() {
        return kind == Kind.GONE;
    }

    private enum Kind {
        TEMPORARY, PERMANENT, GONE
    }
}
