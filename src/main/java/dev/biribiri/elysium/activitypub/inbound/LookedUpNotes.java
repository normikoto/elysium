package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Optional;

/**
 * Stores a status that a local account looked up by a URL, for {@code activitypub.resolve}, which fetches the URL without
 * knowing yet whether it's a status.
 *
 * <p>The URL can be a page that serves the note with another ID, and any server can serve a page naming any ID, so the note
 * is only trusted once it's fetched from its own ID, as a boosted note is. A note naming one that's stored already is
 * answered with the stored one, which the caller still has to check the requester can see.
 */
@Component
public class LookedUpNotes {

    private final ActivityDocuments documents;
    private final NoteStorage storage;
    private final StatusService statuses;
    private final ActorService actors;
    private final DomainBlocks blocks;
    private final InstanceProperties instance;

    LookedUpNotes(
            ActivityDocuments documents,
            NoteStorage storage,
            StatusService statuses,
            ActorService actors,
            DomainBlocks blocks,
            InstanceProperties instance) {
        this.documents = documents;
        this.storage = storage;
        this.statuses = statuses;
        this.actors = actors;
        this.blocks = blocks;
        this.instance = instance;
    }

    /**
     * Whether a fetched document is a {@code Note} or {@code Question} that {@link #store} can read.
     */
    public static boolean isNote(JsonNode document) {
        return RemoteNote.read(document).isPresent();
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @param fetchedFrom the URL the document was fetched from
     * @param document    what was served there
     * @return the stored status, or empty if the note isn't its author's, or isn't one anyone can see
     * @throws IllegalStateException if the note's server couldn't be reached
     */
    public Optional<Status> store(URI fetchedFrom, JsonNode document) {
        var read = RemoteNote.read(document);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        var id = read.get().id();
        var stored = statuses.findStoredByUrl(id.toString());
        if (stored.isPresent()) {
            return stored;
        }
        if (!"https".equalsIgnoreCase(id.getScheme()) || id.getHost() == null
                || id.getHost().equalsIgnoreCase(instance.baseUrl().getHost()) || blocks.isBlocked(id.getHost())) {
            return Optional.empty();
        }
        var note = id.equals(fetchedFrom) ? AttributedNote.of(id, document) : AttributedNote.fetch(id, documents);
        if (note.isEmpty()) {
            return Optional.empty();
        }
        // TAKEN means it was stored since it was looked for, which finds it all the same.
        if (storage.storeLookedUp(note.get().note(), note.get().author()) == NoteStorage.Result.IGNORED) {
            return Optional.empty();
        }
        return actors.findByUri(note.get().author())
                .filter(Actor::isActive)
                .flatMap(author -> statuses.findStoredByUri(id, author.id()));
    }
}
