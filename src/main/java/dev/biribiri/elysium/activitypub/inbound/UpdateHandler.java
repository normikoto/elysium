package dev.biribiri.elysium.activitypub.inbound;

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

/**
 * Applies a change that another server made to one of its actors, or to a status stored here.
 *
 * <p>An actor can only update itself, and an {@code Update} of any other actor is ignored, as {@link ActorNotes} ignores
 * anything that isn't a {@code Note}. Its document is fetched again rather than read from the activity, because not every
 * server says when an actor changed, so an older {@code Update} that runs after a newer one couldn't be told apart. One
 * that isn't stored is ignored, as for a {@code Delete}: Mastodon sends these to every server it knows.
 *
 * <p>A {@code Note} has to be the actor's, as {@link ActorNotes} checks, and already stored, so that editing an old post
 * can't make it look new here, at the cost of losing an edit that runs before its {@code Create}. Its {@code updated}
 * orders edits, since jobs have no order, and one without it is ignored, because nothing says that it's newer than what's
 * stored.
 */
@Component
class UpdateHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdateHandler.class);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final StatusService statuses;
    private final ActorNotes notes;
    private final DomainBlocks blocks;
    private final JsonMapper json;

    UpdateHandler(
            ActorService actors,
            RemoteActors remoteActors,
            StatusService statuses,
            ActorNotes notes,
            DomainBlocks blocks,
            JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.statuses = statuses;
        this.notes = notes;
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
        var author = actors.findByUri(actorUri).filter(found -> found.isActive() && !actors.isLocal(found));
        if (author.isEmpty()) {
            log.debug("Ignoring Update {}, whose actor isn't stored here", activity.id());
            return;
        }
        var changed = statuses.updateRemote(new RemoteEdit(
                note.id(),
                author.get().id(),
                note.content() == null ? "" : note.content(),
                note.summary(),
                note.language(),
                note.sensitive(),
                note.updated()));
        if (!changed) {
            log.debug("Ignoring Update {}, whose Note isn't stored, or has a newer version", activity.id());
        }
    }
}
