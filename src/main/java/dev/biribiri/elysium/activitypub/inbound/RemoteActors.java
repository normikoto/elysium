package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Keeps a copy of actors from other servers, fetching their documents when there's no copy or the copy is old.
 */
@Component
public class RemoteActors {

    private static final Logger log = LoggerFactory.getLogger(RemoteActors.class);

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
    public Actor fetchOrRefresh(URI uri) {
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
        if (existing.filter(actor -> !actor.isActive()).isPresent()) {
            throw new PermanentJobFailure("The actor " + uri + " is suspended or deleted");
        }
        if (existing.filter(RemoteActors::isFresh).isPresent()) {
            return existing.get();
        }
        var document = fetch(uri, existing.isPresent());
        return document.map(fetched -> actors.upsertRemote(RemoteActorDocument.read(uri, fetched)))
                .or(() -> existing)
                .orElseThrow();
    }

    /**
     * Whether a stored copy is recent enough that {@link #fetchOrRefresh} would use it without fetching.
     */
    public static boolean isFresh(Actor actor) {
        return actor.updatedAt().isAfter(Instant.now().minus(KEPT_FOR));
    }

    /**
     * @param hasCopy whether there's an older copy that can be used if this fails. One failed request, or a firewall's answer,
     *                is no reason to lose an actor. A server saying that the actor is gone is.
     * @return the document, or empty if the older copy should be used
     */
    private Optional<JsonNode> fetch(URI uri, boolean hasCopy) {
        try {
            return Optional.of(documents.fetch(uri));
        } catch (DocumentFetchException e) {
            if (hasCopy && !e.isGone()) {
                log.debug("Using an older copy of {}: {}", uri, e.getMessage());
                return Optional.empty();
            }
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            throw new PermanentJobFailure(e.getMessage(), e);
        }
    }
}
