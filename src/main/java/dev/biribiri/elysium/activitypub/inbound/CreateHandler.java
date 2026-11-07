package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Stores a {@code Note} from another server as a status, when it's for a local account, through a follow or by addressing
 * it. Notes that nobody here would see aren't kept, which also stops any server from filling the database by delivering to
 * the shared inbox. Direct notes are left out until mentions exist, since nobody but their author could see them here.
 *
 * <p>Only the activity's actor has been checked, so the note has to be theirs: its {@code attributedTo} has to be that actor,
 * and its ID on their server. A note sent only as an ID is fetched from there.
 */
@Component
class CreateHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateHandler.class);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final StatusService statuses;
    private final LocalRecipients recipients;
    private final ActivityDocuments documents;
    private final DomainBlocks blocks;
    private final JsonMapper json;

    CreateHandler(
            ActorService actors,
            RemoteActors remoteActors,
            StatusService statuses,
            LocalRecipients recipients,
            ActivityDocuments documents,
            DomainBlocks blocks,
            JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.statuses = statuses;
        this.recipients = recipients;
        this.documents = documents;
        this.blocks = blocks;
        this.json = json;
    }

    @Override
    public String type() {
        return "Create";
    }

    @Override
    public void handle(InboundActivity activity) {
        var actorUri = URI.create(activity.actorUri());
        var document = json.readTree(activity.body());
        var object = embeddedObject(document);
        if (object.isEmpty()) {
            log.debug("Ignoring Create {}, which doesn't have one object", activity.id());
            return;
        }
        var fetched = object.get().isString();
        var embeddedNote = fetched ? Optional.<RemoteNote>empty() : RemoteNote.read(object.get());
        var noteId = fetched
                ? Optional.ofNullable(ActivityJson.text(object.get())).flatMap(ActivityObject::parseUri)
                : embeddedNote.map(RemoteNote::id);
        if (noteId.isEmpty()) {
            log.debug("Ignoring Create {}, whose object isn't a Note", activity.id());
            return;
        }
        // Checked before fetching, since the actor can't speak for anyone else's objects, and nothing should be fetched
        // from another server on their word.
        if (!InboundActivity.isStorableId(noteId.get().toString()) || !Inbox.isOnActorsServer(noteId.get(), actorUri)) {
            log.debug("Dropping Create {}, whose object isn't on its actor's server", activity.id());
            return;
        }
        var note = fetched ? fetch(activity, noteId.get()) : embeddedNote;
        if (note.isEmpty()) {
            log.debug("Ignoring Create {}, whose object isn't a Note", activity.id());
            return;
        }
        if (!note.get().attributedTo().equals(List.of(activity.actorUri()))) {
            log.debug("Dropping Create {}, whose Note isn't attributed to its actor alone", activity.id());
            return;
        }

        // Before anything that reads the author's followers collection, which is only known once the actor is stored. It
        // can make a request to another server.
        var author = remoteActors.fetchOrRefresh(actorUri);
        var visibility = visibility(note.get().addressing(), author);
        if (visibility == Visibility.DIRECT) {
            log.debug("Ignoring Create {}, whose Note is direct", activity.id());
            return;
        }
        var forWhom = recipients.of(activity, document, fetched ? note.get().addressing() : Addressing.NONE);
        if (forWhom.isEmpty()) {
            log.debug("Ignoring Create {}, which isn't for any local account", activity.id());
            return;
        }
        // Fetching can take a while, and a block or suspension can have landed since the job started.
        if (blocks.isBlocked(actorUri.getHost()) || actors.isInactive(actorUri)) {
            return;
        }
        statuses.storeRemote(new RemoteStatus(
                note.get().id(),
                note.get().url(),
                author.id(),
                note.get().content() == null ? "" : note.get().content(),
                note.get().summary(),
                note.get().language(),
                visibility,
                note.get().sensitive(),
                note.get().inReplyTo(),
                note.get().published() == null ? activity.firstReceivedAt() : note.get().published()));
    }

    /**
     * The object, as an embedded object or a string that should be its ID, or empty if there isn't one.
     */
    private static Optional<JsonNode> embeddedObject(JsonNode activity) {
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
            log.debug("Dropping Create {}, whose object couldn't be fetched: {}", activity.id(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read from the note, as Mastodon reads it, rather than from its {@code Create}, since the note is what's stored. A note
     * to its author's followers is followers-only even if it also names other actors.
     */
    private Visibility visibility(Addressing addressing, Actor author) {
        if (addressing.to().contains(ActivityStreams.PUBLIC)) {
            return Visibility.PUBLIC;
        }
        if (addressing.cc().contains(ActivityStreams.PUBLIC)) {
            return Visibility.UNLISTED;
        }
        var followers = actors.findFollowersUri(author.uri()).map(URI::toString);
        if (followers.filter(uri -> addressing.to().contains(uri) || addressing.cc().contains(uri)).isPresent()) {
            return Visibility.PRIVATE;
        }
        return Visibility.DIRECT;
    }
}
