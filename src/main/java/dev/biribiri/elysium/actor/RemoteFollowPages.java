package dev.biribiri.elysium.actor;

import java.net.URI;
import java.util.Optional;

/**
 * Finds where an account on another server can follow someone, so that a visitor who enters their address can be sent to
 * their own server, which then sends the {@code Follow}. The {@code activitypub} module implements this.
 *
 * <p>This blocks while asking that server, so it mustn't be called inside a database transaction. It sends a request to a
 * server of the caller's choosing, so callers limit how often anyone can ask.
 */
public interface RemoteFollowPages {

    /**
     * @param target the actor to follow
     * @return the page on the account's server, or empty if the address is malformed or this server's, its server is
     * blocked or doesn't say where, or it can't be reached
     */
    Optional<URI> find(String username, String domain, URI target);
}
