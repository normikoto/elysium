package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Reads the {@code Note} that a {@code Create} or {@code Update} is about, and makes sure that it's the activity's actor's.
 * Only the activity's actor has been checked, so the note's {@code attributedTo} has to be that actor, and its ID on their
 * server. A note sent only as an ID is fetched from there.
 */
@Component
class ActorNotes {

    private static final Logger log = LoggerFactory.getLogger(ActorNotes.class);

    private final ActivityDocuments documents;

    ActorNotes(ActivityDocuments documents) {
        this.documents = documents;
    }

    /**
     * @param fetched whether the note was fetched, rather than embedded in the activity, so that its addressing wasn't part
     *                of what the actor sent
     */
    record ActorNote(RemoteNote note, boolean fetched) {
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @param document the activity's JSON
     * @return the note, or empty if the object isn't one {@code Note} of the actor's
     * @throws IllegalStateException if the actor's server couldn't be reached, which is worth trying again
     */
    Optional<ActorNote> read(InboundActivity activity, JsonNode document) {
        var actorUri = URI.create(activity.actorUri());
        var object = object(document);
        if (object.isEmpty()) {
            log.debug("Ignoring {} {}, which doesn't have one object", activity.type(), activity.id());
            return Optional.empty();
        }
        var fetched = object.get().isString();
        var embeddedNote = fetched ? Optional.<RemoteNote>empty() : RemoteNote.read(object.get());
        var noteId = fetched
                ? Optional.ofNullable(ActivityJson.text(object.get())).flatMap(ActivityObject::parseUri)
                : embeddedNote.map(RemoteNote::id);
        if (noteId.isEmpty()) {
            log.debug("Ignoring {} {}, whose object isn't a Note", activity.type(), activity.id());
            return Optional.empty();
        }
        // Checked before fetching, since the actor can't speak for anyone else's objects, and nothing should be fetched
        // from another server on their word.
        if (!InboundActivity.isStorableId(noteId.get().toString()) || !Inbox.isOnActorsServer(noteId.get(), actorUri)) {
            log.debug("Dropping {} {}, whose object isn't on its actor's server", activity.type(), activity.id());
            return Optional.empty();
        }
        var note = fetched ? fetch(activity, noteId.get()) : embeddedNote;
        if (note.isEmpty()) {
            log.debug("Ignoring {} {}, whose object isn't a Note", activity.type(), activity.id());
            return Optional.empty();
        }
        if (!note.get().attributedTo().equals(List.of(activity.actorUri()))) {
            log.debug("Dropping {} {}, whose Note isn't attributed to its actor alone", activity.type(), activity.id());
            return Optional.empty();
        }
        return Optional.of(new ActorNote(note.get(), fetched));
    }

    /**
     * The object, as an embedded object or a string that should be its ID, or empty if there isn't one.
     */
    private static Optional<JsonNode> object(JsonNode activity) {
        var node = activity.path("object");
        if (node.isArray()) {
            node = node.size() == 1 ? node.path(0) : null;
        }
        return Optional.ofNullable(node).filter(object -> object.isObject() || object.isString());
    }

    /**
     * @param id an object on the actor's own server
     * @return the note, or empty if what's served there isn't a {@code Note} with this ID
     * @throws IllegalStateException if the actor's server couldn't be reached, which is worth trying again
     */
    private Optional<RemoteNote> fetch(InboundActivity activity, URI id) {
        try {
            return RemoteNote.read(documents.fetch(id)).filter(note -> note.id().equals(id));
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Dropping {} {}, whose object couldn't be fetched: {}", activity.type(), activity.id(), e.getMessage());
            return Optional.empty();
        }
    }
}
