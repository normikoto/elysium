package dev.biribiri.elysium.activitypub.signature;

import java.net.URI;
import java.util.Optional;

/**
 * A request's signature couldn't be accepted. The message can include values from the request, so it's for logs rather
 * than for sending back.
 */
public class SignatureVerificationException extends RuntimeException {

    private final Failure failure;
    private final URI keyDocument;

    SignatureVerificationException(Failure failure, String message) {
        this(failure, message, null);
    }

    private SignatureVerificationException(Failure failure, String message, URI keyDocument) {
        super(message);
        this.failure = failure;
        this.keyDocument = keyDocument;
    }

    static SignatureVerificationException keyGone(URI keyDocument, String message) {
        return new SignatureVerificationException(Failure.KEY_GONE, message, keyDocument);
    }

    static SignatureVerificationException suspended(URI actor, String message) {
        return new SignatureVerificationException(Failure.ACTOR_SUSPENDED, message, actor);
    }

    public Failure failure() {
        return failure;
    }

    /**
     * @return for {@link Failure#KEY_GONE}, the document the key was in, which is usually its actor's, for
     * {@link Failure#ACTOR_SUSPENDED}, the actor that's refused, and otherwise empty. Either way, that actor's own
     * {@code Delete} can still be checked with its server.
     */
    public Optional<URI> keyDocument() {
        return Optional.ofNullable(keyDocument);
    }

    /**
     * Why a signature wasn't accepted, and the status to respond with. These match Mastodon's, which other servers may
     * act on: Mastodon's own client retries with an RFC 9421 signature after a 400 or 401.
     */
    public enum Failure {
        /** The signature header couldn't be read. */
        MALFORMED(400),
        /** The request has no signature in a format this server supports. */
        NO_SUPPORTED_SIGNATURE(401),
        /** The signature was read, but it isn't valid or isn't good enough. */
        REJECTED(401),
        /**
         * The key's server answered that it's gone, which is what happens to the key of an actor that's been deleted. Its
         * actor's own {@code Delete} can still be checked another way.
         */
        KEY_GONE(401),
        /** The key is on a server that's been blocked, so it's never looked up. This tells the sender not to try again. */
        DOMAIN_BLOCKED(403),
        /** The key's actor has been suspended or deleted, so it isn't looked up or accepted. */
        ACTOR_SUSPENDED(403),
        /** The sender's key can't be fetched right now, so the sender should try again later. */
        KEY_UNAVAILABLE(503),
        /** Checking this signature needs a key fetch, and the sender has caused too many. */
        FETCH_BUDGET_SPENT(429);

        private final int status;

        Failure(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
