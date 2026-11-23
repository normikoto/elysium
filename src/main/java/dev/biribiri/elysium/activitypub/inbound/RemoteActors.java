package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.signature.SignatureVerifier;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.RemoteEmoji;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final SignatureVerifier signatures;
    private final DomainBlocks blocks;
    private final CustomEmojis emojis;
    private final TransactionTemplate transaction;

    RemoteActors(
            ActorService actors,
            ActivityDocuments documents,
            InstanceProperties instance,
            SignatureVerifier signatures,
            DomainBlocks blocks,
            CustomEmojis emojis,
            PlatformTransactionManager transactionManager) {
        this.emojis = emojis;
        this.transaction = new TransactionTemplate(transactionManager);
        this.actors = actors;
        this.documents = documents;
        this.instance = instance;
        this.signatures = signatures;
        this.blocks = blocks;
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @param uri an actor named by a valid signature
     * @return the stored actor
     * @throws PermanentJobFailure if the actor can never be stored. Anything else thrown is worth trying again.
     */
    public Actor fetchOrRefresh(URI uri) {
        checkUri(uri);
        var existing = actors.findByUri(uri);
        if (existing.filter(actor -> !actor.isActive()).isPresent()) {
            throw new PermanentJobFailure("The actor " + uri + " is suspended or deleted");
        }
        if (existing.filter(RemoteActors::isFresh).isPresent()) {
            return existing.get();
        }
        var document = fetch(uri, existing.isPresent());
        var stored = document
                .map(fetched -> store(RemoteActorDocument.read(uri, fetched), RemoteActorDocument.emoji(fetched)))
                .or(() -> existing)
                .orElseThrow();
        // It can have been suspended or deleted while it was being fetched, which the upsert leaves alone.
        if (!stored.isActive()) {
            throw new PermanentJobFailure("The actor " + uri + " is suspended or deleted");
        }
        return stored;
    }

    /**
     * Fetches a stored actor's document again, however recent the stored copy is, for when the actor says that it changed.
     * The document is fetched rather than taken from what the actor sent, because it's always the latest, whatever order
     * the actor's activities run in. Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @param uri an actor named by a valid signature
     * @return the actor, or empty if it isn't stored here, isn't active, or its domain is blocked. The older copy is kept if its server refuses to
     * serve it, or says it's gone, since only its {@code Delete} removes it.
     * @throws PermanentJobFailure if the actor can never be stored, or its new document can't be used, which leaves the
     *                             older copy. Anything else thrown is worth trying again.
     */
    public Optional<Actor> refresh(URI uri) {
        checkUri(uri);
        var existing = actors.findByUri(uri).filter(actor -> actor.isActive() && !actors.isLocal(actor));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        JsonNode document;
        try {
            document = documents.fetch(uri);
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // The actor's Delete is what removes it, so the older copy is kept until that arrives.
            log.debug("Keeping the older copy of {}: {}", uri, e.getMessage());
            return existing;
        }
        return storeFetched(uri, document, true, null);
    }

    /**
     * Fetches an actor's document whether or not it's stored, never settling for an older copy, for when what it says right
     * now matters: whether it lists another account in {@code alsoKnownAs}, which it may have only just started to. Blocks
     * while fetching, so this mustn't be called inside a database transaction.
     *
     * @param alias an account that's checked for in the actor's {@code alsoKnownAs}, which is then stored even if the
     *              document lists more aliases than are kept
     * @return the stored actor, or empty if it isn't active or its domain is blocked
     * @throws PermanentJobFailure if the actor can't be fetched or stored. Anything else thrown is worth trying again.
     */
    public Optional<Actor> fetchFresh(URI uri, URI alias) {
        checkUri(uri);
        if (blocks.isBlocked(uri.getHost())) {
            return Optional.empty();
        }
        var existing = actors.findByUri(uri);
        if (existing.filter(actor -> !actor.isActive()).isPresent()) {
            return Optional.empty();
        }
        return storeFetched(uri, fetch(uri, false).orElseThrow(), existing.isPresent(), alias);
    }

    /**
     * @param stored whether the actor was already stored, whose keys may then have changed
     */
    private Optional<Actor> storeFetched(URI uri, JsonNode document, boolean stored, URI alias) {
        if (stored) {
            // Before the document is checked, so that a key it drops isn't still trusted when the rest can't be used. The
            // document came from the actor's own URI, so it speaks for the actor's keys whatever else is wrong with it.
            signatures.forgetChangedKeys(uri, document);
        }
        var profile = RemoteActorDocument.read(uri, document, alias);
        // Fetching can take a while, and a block can have landed since the job started. A suspension or deletion is left
        // alone by the upsert.
        if (blocks.isBlocked(uri.getHost())) {
            return Optional.empty();
        }
        return Optional.of(store(profile, RemoteActorDocument.emoji(document))).filter(Actor::isActive);
    }

    /**
     * The emoji are stored with the profile that lists them, since one stored by itself could be removed as unused before
     * the profile keeps it.
     */
    private Actor store(RemoteActorProfile profile, List<RemoteEmoji> emoji) {
        return transaction.execute(status -> {
            var stored = actors.upsertRemote(profile);
            if (stored.isActive()) {
                var ids = emojis.storeRemote(stored.domain(), emoji);
                emojis.replaceForRemoteProfile(stored.id(), ids.values());
            }
            return stored;
        });
    }

    private void checkUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new PermanentJobFailure("The actor " + uri + " isn't an HTTPS URL");
        }
        if (!InboundActivity.isStorableId(uri.toString())) {
            throw new PermanentJobFailure("An actor's URI is too long to store");
        }
        if (instance.isLocalDomain(RemoteActorDocument.domainOf(uri))) {
            // A remote row on this server's authority could take the URI of a local account that doesn't exist yet, and
            // one on the account domain would be stored with local accounts' domain, and so be mistaken for one.
            throw new PermanentJobFailure("The actor " + uri + " claims to be on this server");
        }
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
