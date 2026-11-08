package dev.biribiri.elysium.actor;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds actors on other servers by asking those servers, for modules that can't depend on the federation code. The
 * {@code activitypub} module implements this.
 *
 * <p>These block while making requests, so they mustn't be called inside a database transaction. Each call counts toward a
 * limit for the local account it's made for, because it makes this server send requests to a server of the caller's
 * choosing.
 */
public interface RemoteActorResolver {

    /**
     * How many lookups each local account can ask for in a minute. Each one can make a few requests to a server the caller
     * chooses.
     */
    int LOOKUPS_PER_MINUTE = 30;

    /**
     * Finds an actor through WebFinger, storing it if it's new or the stored copy is old.
     *
     * @param requester the local account asking, whose limit this counts toward
     * @return the stored actor, or empty if there's no such actor, it can't be fetched right now, or its server is blocked
     * @throws ResolveLimitException if the requester has made too many lookups recently
     */
    Optional<Actor> resolveAcct(UUID requester, String username, String domain);

    /**
     * Finds an actor that's already stored, without asking its server, so this never makes a request and doesn't count
     * toward any limit. A stored actor can be on a domain that's been blocked since, until its removal has run, so this
     * leaves those out, as {@link #resolveAcct} does.
     *
     * @return the stored actor, or empty if there's none, it's suspended or deleted, or its server is blocked
     */
    Optional<Actor> findStored(String username, String domain);

    /**
     * Finds the actor at a URL, which can be its ID or a page whose server serves the actor at the same URL, such as a
     * profile page.
     *
     * @param requester the local account asking, whose limit this counts toward
     * @return the stored actor, or empty if the URL isn't an actor on another server, it can't be fetched right now, or its
     * server is blocked
     * @throws ResolveLimitException if the requester has made too many lookups recently
     */
    Optional<Actor> resolveUrl(UUID requester, URI url);

    /**
     * Thrown when an account has asked for too many lookups recently.
     */
    class ResolveLimitException extends RuntimeException {

        public ResolveLimitException() {
            super("Too many lookups of accounts on other servers");
        }
    }
}
