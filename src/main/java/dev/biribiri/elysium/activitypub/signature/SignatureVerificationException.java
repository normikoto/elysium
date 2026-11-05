package dev.biribiri.elysium.activitypub.signature;

/**
 * A request's signature couldn't be accepted. The message can include values from the request, so it's for logs rather
 * than for sending back.
 */
public class SignatureVerificationException extends RuntimeException {

    private final Failure failure;

    SignatureVerificationException(Failure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
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
        /** The key is on a server that's been blocked, so it's never looked up. This tells the sender not to try again. */
        DOMAIN_BLOCKED(403),
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
