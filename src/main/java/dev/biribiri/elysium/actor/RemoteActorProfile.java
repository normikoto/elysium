package dev.biribiri.elysium.actor;

import java.net.URI;

/**
 * What's stored about an actor on another server.
 *
 * @param domain         the host of {@code uri}, plus its port if it isn't the default
 * @param outboxUri      the actor's outbox, or null if it doesn't have one
 * @param sharedInboxUri the actor's shared inbox, or null if it doesn't have one
 * @param publicKeyPem   the actor's public key, or null if its document doesn't include one
 * @param followersUri   the actor's followers collection, or null if it doesn't have one
 * @param featuredUri    the collection of the actor's pinned statuses, or null if it doesn't have one
 */
public record RemoteActorProfile(
        URI uri,
        String username,
        String domain,
        URI inboxUri,
        URI outboxUri,
        URI sharedInboxUri,
        String publicKeyPem,
        URI followersUri,
        URI featuredUri
) {

    /**
     * An actor with no followers collection.
     */
    public RemoteActorProfile(
            URI uri, String username, String domain, URI inboxUri, URI outboxUri, URI sharedInboxUri, String publicKeyPem) {
        this(uri, username, domain, inboxUri, outboxUri, sharedInboxUri, publicKeyPem, null, null);
    }

    /**
     * An actor with no featured collection.
     */
    public RemoteActorProfile(
            URI uri, String username, String domain, URI inboxUri, URI outboxUri, URI sharedInboxUri, String publicKeyPem,
            URI followersUri) {
        this(uri, username, domain, inboxUri, outboxUri, sharedInboxUri, publicKeyPem, followersUri, null);
    }
}
