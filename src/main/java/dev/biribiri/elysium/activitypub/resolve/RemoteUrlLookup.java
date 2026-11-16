package dev.biribiri.elysium.activitypub.resolve;

import dev.biribiri.elysium.activitypub.inbound.LookedUpNotes;
import dev.biribiri.elysium.activitypub.inbound.RemoteActors;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.RemoteUrlResolver;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the account or status at a URL for local accounts, fetching it once and going by what's served there, as Mastodon
 * does. Either can be served at a page with another ID, which is then fetched from that ID, so that only its own server
 * decides what it says. Lookups count toward the same {@link ResolveLimits} as {@link RemoteActorLookup}'s, since either
 * kind makes this server send requests to a server of the requester's choosing.
 *
 * <p>Unlike threads and boosts, these fetches don't wait for a turn in {@code TargetFetchTurns}: a local account chooses the
 * URL rather than another server, and its own limit already bounds how often it can.
 */
@Component
class RemoteUrlLookup implements RemoteUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(RemoteUrlLookup.class);

    private final RemoteActors remoteActors;
    private final ResolveLimits limits;
    private final LookedUpNotes notes;
    private final ActorService actors;
    private final StatusService statuses;
    private final ActivityDocuments documents;
    private final DomainBlocks blocks;
    private final InstanceProperties instance;

    RemoteUrlLookup(
            RemoteActors remoteActors,
            ResolveLimits limits,
            LookedUpNotes notes,
            ActorService actors,
            StatusService statuses,
            ActivityDocuments documents,
            DomainBlocks blocks,
            InstanceProperties instance) {
        this.remoteActors = remoteActors;
        this.limits = limits;
        this.notes = notes;
        this.actors = actors;
        this.statuses = statuses;
        this.documents = documents;
        this.blocks = blocks;
        this.instance = instance;
    }

    @Override
    public Optional<Found> resolveUrl(UUID requester, URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || blocks.isBlocked(url.getHost())) {
            return Optional.empty();
        }
        var stored = statuses.findStoredByUrl(url.toString());
        if (stored.isPresent()) {
            return Optional.of(new Found.Post(stored.get()));
        }
        var storedActor = actors.findByUri(url).filter(found -> !actors.isLocal(found));
        if (storedActor.filter(RemoteActors::isFresh).isPresent()) {
            return account(storedActor);
        }
        if (url.getHost().equalsIgnoreCase(instance.baseUrl().getHost())) {
            return Optional.empty();
        }
        limits.count(requester);
        if (storedActor.isPresent()) {
            // A copy that can't be refreshed for now is still better than nothing, as for a lookup by address.
            return account(fetchActor(url).or(() -> storedActor));
        }
        JsonNode document;
        try {
            document = documents.fetch(url);
        } catch (DocumentFetchException e) {
            log.debug("Couldn't look up {}: {}", url, e.getMessage());
            return Optional.empty();
        }
        if (ActivityDocuments.isActor(document)) {
            return account(RemoteActorLookup.parse(document.path("id").stringValue(null)).flatMap(this::fetchActor));
        }
        if (!LookedUpNotes.isNote(document)) {
            return Optional.empty();
        }
        try {
            return notes.store(url, document).map(Found.Post::new);
        } catch (RuntimeException e) {
            // Its server can't be reached, or its author can't be fetched or posts nothing here, and there's nothing to show.
            log.debug("Couldn't store the status at {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Actor> fetchActor(URI actorUri) {
        return RemoteActorLookup.fetch(remoteActors, blocks, actorUri);
    }

    private static Optional<Found> account(Optional<Actor> actor) {
        return actor.filter(Actor::isActive).map(Found.Account::new);
    }
}
