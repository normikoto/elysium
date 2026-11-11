package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.QuoteService;
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
import java.time.Instant;
import java.util.Optional;

/**
 * Applies a change that another server made to one of its actors, or to a status stored here.
 *
 * <p>An actor can only update itself, and an {@code Update} of any other actor is ignored, as {@link ActorNotes} ignores
 * anything that isn't a {@code Note}. Its document is fetched again rather than read from the activity, because not every
 * server says when an actor changed, so an older {@code Update} that runs after a newer one couldn't be told apart. One
 * that isn't stored is ignored, as for a {@code Delete}: Mastodon sends these to every server it knows.
 *
 * <p>A {@code Note} has to be the actor's, as {@link ActorNotes} checks. Its {@code updated} orders edits, since jobs have
 * no order, and one without it isn't applied as an edit, because nothing says that it's newer than what's stored. What it
 * quotes, and who can quote it, are applied either way, as {@link QuoteService#reconcileRemote} explains. One that isn't stored
 * yet is stored as a {@code Create} would store it, if it was published recently, since its {@code Create} can run later.
 */
@Component
class UpdateHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdateHandler.class);

    /**
     * How old a note can be and still be stored from an {@code Update}. A {@code Create} and its {@code Update} are
     * usually minutes apart, and a {@code Create} that's held up longer than jobs are retried for isn't coming.
     */
    private static final Duration NEW_FOR = Jobs.RETRIED_FOR;

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final StatusService statuses;
    private final ActorNotes notes;
    private final NoteStorage storage;
    private final DomainBlocks blocks;
    private final QuoteService quotes;
    private final JsonMapper json;

    UpdateHandler(
            ActorService actors,
            RemoteActors remoteActors,
            StatusService statuses,
            ActorNotes notes,
            NoteStorage storage,
            DomainBlocks blocks,
            QuoteService quotes,
            JsonMapper json) {
        this.quotes = quotes;
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
        // Fetching can take a while, and a block or suspension can have landed since the job started.
        if (blocks.isBlocked(actorUri.getHost()) || actors.isInactive(actorUri)) {
            return;
        }
        applyEdit(activity, actorUri, document, read.get());
        // Whatever its updated says, since servers send an Update when a quote is approved, and the approval is checked
        // with whoever gave it rather than ordered by time. Found again, since storing a late note can store its author.
        storedAuthor(actorUri).ifPresent(author -> quotes.reconcileRemote(
                note.id(), author.id(), note.quote(), storage.quotePolicy(note.quotePolicy(), author)));
    }

    private void applyEdit(InboundActivity activity, URI actorUri, JsonNode document, ActorNotes.ActorNote read) {
        var note = read.note();
        if (note.editedAt(note.published(), Instant.now()) == null) {
            log.debug("Not editing for Update {}, whose Note doesn't say when it was edited, or says a time that can't be",
                    activity.id());
            return;
        }
        // An author who isn't stored can't have a stored status, but can still have one to store, if its Create is late.
        var outcome = storedAuthor(actorUri)
                .map(author -> edit(note, author))
                .orElse(RemoteEdit.Outcome.NOT_STORED);
        switch (outcome) {
            case EDITED -> {
            }
            case UNCHANGED -> log.debug("Not editing for Update {}, whose Note has a newer version, is the same, or is "
                    + "someone else's", activity.id());
            case NOT_STORED -> storeLate(activity, document, read);
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
        if (storage.store(activity, document, read) == NoteStorage.Result.TAKEN) {
            // Its Create was stored in between, with an older version, which can still be edited. Or it was deleted, which
            // this finds nothing to edit for.
            storedAuthor(URI.create(activity.actorUri())).ifPresent(author -> edit(read.note(), author));
        }
    }

    /**
     * @return the actor, if it's stored here, active, and remote, as an author of remote statuses has to be
     */
    private Optional<Actor> storedAuthor(URI actorUri) {
        return actors.findByUri(actorUri).filter(found -> found.isActive() && !actors.isLocal(found));
    }

    private RemoteEdit.Outcome edit(RemoteNote note, Actor author) {
        return statuses.updateRemote(new RemoteEdit(
                note.id(),
                author.id(),
                note.content() == null ? "" : note.content(),
                note.summary(),
                note.language(),
                note.sensitive(),
                note.editedAt(note.published(), Instant.now()),
                note.hashtags(),
                storage.attachments(note, author)));
    }
}
