package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps a copy of actors from other servers, fetching their documents when there's no copy or the copy is old.
 */
@Component
class RemoteActors {

    private static final Duration KEPT_FOR = Duration.ofHours(24);

    private final ActorService actors;
    private final ActivityDocuments documents;
    private final InstanceProperties instance;

    RemoteActors(ActorService actors, ActivityDocuments documents, InstanceProperties instance) {
        this.actors = actors;
        this.documents = documents;
        this.instance = instance;
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @param uri an actor named by a valid signature
     * @return the stored actor
     * @throws PermanentJobFailure if the actor can never be stored. Anything else thrown is worth trying again.
     */
    Actor fetchOrRefresh(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new PermanentJobFailure("The actor " + uri + " isn't an HTTPS URL");
        }
        if (!InboundActivity.isStorableId(uri.toString())) {
            throw new PermanentJobFailure("An actor's URI is too long to store");
        }
        if (RemoteActorDocument.domainOf(uri).equals(instance.domain())) {
            // A remote row claiming this domain could take the URI of a local account that doesn't exist yet.
            throw new PermanentJobFailure("The actor " + uri + " claims to be on this server");
        }
        var existing = actors.findByUri(uri);
        if (existing.isPresent() && existing.get().updatedAt().isAfter(Instant.now().minus(KEPT_FOR))) {
            return existing.get();
        }
        var profile = RemoteActorDocument.read(uri, fetch(uri));
        return actors.upsertRemote(profile);
    }

    private JsonNode fetch(URI uri) {
        try {
            return documents.fetch(uri);
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            throw new PermanentJobFailure(e.getMessage(), e);
        }
    }
}
