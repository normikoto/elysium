package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.RemoteEdit;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Applies a change that another server made to one of its actors, or to a status stored here.
 *
 * <p>An actor can only update itself, and an {@code Update} of any other actor is ignored, as {@link ActorNotes} ignores
 * anything that isn't a {@code Note}. Its document is fetched again rather than read from the activity, because not every
 * server says when an actor changed, so an older {@code Update} that runs after a newer one couldn't be told apart. One
 * that isn't stored is ignored, as for a {@code Delete}: Mastodon sends these to every server it knows.
 *
 * <p>A {@code Note} has to be the actor's, as {@link ActorNotes} checks. Its {@code updated} orders edits, since jobs have
 * no order, and one without it is ignored, because nothing says that it's newer than what's stored. One that isn't stored
 * yet is stored as a {@code Create} would store it, if it was published recently, since its {@code Create} can run later.
 */
@Component
class UpdateHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdateHandler.class);

    /**
     * How old a note can be and still be stored from an {@code Update}. A {@code Create} and its {@code Update} are
     * usually minutes apart, and this matches how long deliveries to a host that's backing off are kept.
     */
    private static final Duration NEW_FOR = Duration.ofDays(3);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final StatusService statuses;
    private final ActorNotes notes;
    private final NoteStorage storage;
    private final DomainBlocks blocks;
    private final JsonMapper json;

    UpdateHandler(
            ActorService actors,
            RemoteActors remoteActors,
            StatusService statuses,
            ActorNotes notes,
            NoteStorage storage,
            DomainBlocks blocks,
            JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.statuses = statuses;
        this.notes = notes;
        this.storage = storage;
        this.blocks = blocks;
        this.json = json;
    }

    @Override
    public String type() {
        return "Update";
    }

    @Override
    public void handle(InboundActivity activity) {
        var actorUri = URI.create(activity.actorUri());
        var document = json.readTree(activity.body());
        var object = ActivityObject.of(document);
        if (object.isEmpty()) {
            log.debug("Ignoring Update {}, which doesn't have one object", activity.id());
            return;
        }
        if (activity.actorUri().equals(object.get().id())) {
            updateActor(activity, actorUri);
            return;
        }
        updateNote(activity, actorUri, document);
    }

    private void updateActor(InboundActivity activity, URI actorUri) {
        if (remoteActors.refresh(actorUri).isEmpty()) {
            log.debug("Ignoring Update {}, whose actor isn't stored here", activity.id());
        }
    }

    private void updateNote(InboundActivity activity, URI actorUri, JsonNode document) {
        var read = notes.read(activity, document);
        if (read.isEmpty()) {
            return;
        }
        var note = read.get().note();
        if (note.updated() == null) {
            log.debug("Ignoring Update {}, whose Note doesn't say when it was updated", activity.id());
            return;
        }
        // Fetching can take a while, and a block or suspension can have landed since the job started.
        if (blocks.isBlocked(actorUri.getHost()) || actors.isInactive(actorUri)) {
            return;
        }
        // An author who isn't stored can't have a stored status, but can still have one to store, if its Create is late.
        var outcome = actors.findByUri(actorUri)
                .filter(found -> found.isActive() && !actors.isLocal(found))
                .map(author -> edit(note, author.id()))
                .orElse(RemoteEdit.Outcome.NOT_STORED);
        switch (outcome) {
            case EDITED -> {
            }
            case UNCHANGED -> log.debug("Ignoring Update {}, whose Note has a newer version, or is someone else's", activity.id());
            case NOT_STORED -> storeLate(activity, document, read.get());
        }
    }

    /**
     * Stores a note whose {@code Create} hasn't been processed yet, since jobs have no order, as long as it's new. An older
     * note is ignored, so that editing an old post can't make it look new here: status IDs come from when a status arrives,
     * so it would go to the top of timelines. Its {@code published} is the other server's word, but a server that lied could
     * send a {@code Create} instead.
     */
    private void storeLate(InboundActivity activity, JsonNode document, ActorNotes.ActorNote read) {
        var published = read.note().published();
        if (published == null || published.isBefore(activity.firstReceivedAt().minus(NEW_FOR))) {
            log.debug("Ignoring Update {}, whose Note isn't stored, and isn't new", activity.id());
            return;
        }
        if (storage.store(activity, document, read)) {
            return;
        }
        // A Create that's stored in between, with an older version, is still edited.
        var author = actors.findByUri(URI.create(activity.actorUri())).filter(Actor::isActive);
        author.ifPresent(found -> edit(read.note(), found.id()));
    }

    private RemoteEdit.Outcome edit(RemoteNote note, UUID authorId) {
        return statuses.updateRemote(new RemoteEdit(
                note.id(),
                authorId,
                note.content() == null ? "" : note.content(),
                note.summary(),
                note.language(),
                note.sensitive(),
                note.updated()));
    }
}
