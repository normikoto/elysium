package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Decides what to believe from another server's actor document. These values get stored and activities get sent to them, so
 * a document is refused unless it's all usable. Failure messages are shown to admins, so they don't quote the document.
 */
final class RemoteActorDocument {

    private static final int MAX_USERNAME_LENGTH = 255;

    private RemoteActorDocument() {
    }

    /**
     * @return the host and port that would be stored for an actor, as local actors' are
     */
    static String domainOf(URI actor) {
        return HostHeader.value(actor.getScheme(), actor.getHost(), actor.getPort());
    }

    /**
     * @param uri      the actor being looked up, which was named by a valid signature
     * @param document what its server answered with
     * @throws PermanentJobFailure if the document can't be used
     */
    static RemoteActorProfile read(URI uri, JsonNode document) {
        if (!uri.toString().equals(document.path("id").stringValue(null))) {
            throw refused(uri, "has a different id");
        }
        if (!ActivityDocuments.isActor(document)) {
            throw refused(uri, "isn't an actor");
        }
        var username = document.path("preferredUsername").stringValue(null);
        if (username == null || username.isEmpty() || username.length() > MAX_USERNAME_LENGTH
                || username.chars().anyMatch(Character::isISOControl)) {
            throw refused(uri, "doesn't have a usable preferredUsername");
        }
        var domain = domainOf(uri);
        var inbox = inboxOnSameHost(uri, document.path("inbox").stringValue(null));
        if (inbox == null) {
            throw refused(uri, "doesn't have an inbox that's an HTTPS URL on the actor's host");
        }
        // Nothing sends to this yet, so a bad one is a reason to leave it out rather than to lose the actor.
        var sharedInbox = inboxOnSameHost(uri, document.path("endpoints").path("sharedInbox").stringValue(null));
        // Only used to see who an activity is addressed to, so a bad one is left out too. One on another host could make
        // an activity look addressed to the followers of someone else.
        var followers = inboxOnSameHost(uri, document.path("followers").stringValue(null));
        return new RemoteActorProfile(
                uri,
                username,
                domain,
                inbox,
                outbox(document.path("outbox").stringValue(null)),
                sharedInbox,
                publicKeyPem(document.path("publicKey")),
                followers);
    }

    /**
     * Inboxes are where this server sends requests when it's told to, so one on another host would let any server aim this
     * one at a third party. Followers collections are checked the same way.
     *
     * @return the inbox, or null if there isn't one that can be used
     */
    private static URI inboxOnSameHost(URI actor, String value) {
        if (value == null || !InboundActivity.isStorableId(value)) {
            return null;
        }
        return ActivityObject.parseUri(value).filter(inbox -> Inbox.isOnActorsServer(inbox, actor)).orElse(null);
    }

    /**
     * Nothing uses this yet, so a bad one is a reason to leave it out rather than to lose the actor.
     */
    private static URI outbox(String value) {
        if (value == null || !InboundActivity.isStorableId(value)) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * An actor can have one key or several, and each can be just an ID. This stores the first one that's included.
     */
    private static String publicKeyPem(JsonNode publicKey) {
        if (publicKey.isArray()) {
            return publicKey.valueStream()
                    .map(RemoteActorDocument::publicKeyPem)
                    .filter(pem -> pem != null)
                    .findFirst()
                    .orElse(null);
        }
        var pem = publicKey.path("publicKeyPem").stringValue(null);
        // The database can't store a NUL, and a real key doesn't have one.
        return pem == null || pem.indexOf('\0') != -1 ? null : pem;
    }

    private static PermanentJobFailure refused(URI actor, String problem) {
        return new PermanentJobFailure("The actor document of " + actor + " " + problem);
    }
}
