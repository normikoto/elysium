package dev.biribiri.elysium.actor;

import java.net.URI;

/**
 * The actor for the server itself, which signs requests that no user is behind. It isn't an account, so it doesn't count
 * as a user and can't be found by the methods that look up local actors.
 *
 * @param uri the actor's URL. Other servers store this, so it can't change.
 * @param url the page for people to look at, which is the server's front page
 */
public record InstanceActor(
        URI uri,
        URI url,
        String username,
        String domain,
        URI inboxUri,
        URI outboxUri,
        String publicKeyPem
) {

    public static final String PATH = "/actor";

    /**
     * Local usernames can't contain a dot, so no account can ever have this name. It's a fixed name rather than the
     * domain, as other servers can't store a username with a port in it.
     */
    public static final String USERNAME = "instance.actor";

    /**
     * The ID of this actor's public key, which other servers use to find the key that checks its signatures.
     */
    public String publicKeyId() {
        return uri + "#main-key";
    }
}
