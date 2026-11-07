package dev.biribiri.elysium.activitypub.inbound;

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
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * Stores a {@code Note} from another server as a status, when it's for a local account, through a follow or by addressing
 * it. Notes that nobody here would see aren't kept, which also stops any server from filling the database by delivering to
 * the shared inbox. Direct notes are left out until mentions exist, since nobody but their author could see them here.
 *
 * <p>{@link ActorNotes} makes sure the note is the actor's own.
 */
@Component
class CreateHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateHandler.class);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final StatusService statuses;
    private final LocalRecipients recipients;
    private final ActorNotes notes;
    private final DomainBlocks blocks;
    private final JsonMapper json;

    CreateHandler(
            ActorService actors,
            RemoteActors remoteActors,
            StatusService statuses,
            LocalRecipients recipients,
            ActorNotes notes,
            DomainBlocks blocks,
            JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.statuses = statuses;
        this.recipients = recipients;
        this.notes = notes;
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
        var read = notes.read(activity, document);
        if (read.isEmpty()) {
            return;
        }
        var note = read.get().note();

        // Before anything that reads the author's followers collection, which is only known once the actor is stored. It
        // can make a request to another server.
        var author = remoteActors.fetchOrRefresh(actorUri);
        var visibility = visibility(note.addressing(), author);
        if (visibility == Visibility.DIRECT) {
            log.debug("Ignoring Create {}, whose Note is direct", activity.id());
            return;
        }
        var forWhom = recipients.of(activity, document, read.get().fetched() ? note.addressing() : Addressing.NONE);
        if (forWhom.isEmpty()) {
            log.debug("Ignoring Create {}, which isn't for any local account", activity.id());
            return;
        }
        // Fetching can take a while, and a block or suspension can have landed since the job started.
        if (blocks.isBlocked(actorUri.getHost()) || actors.isInactive(actorUri)) {
            return;
        }
        var published = note.published() == null ? activity.firstReceivedAt() : note.published();
        statuses.storeRemote(new RemoteStatus(
                note.id(),
                note.url(),
                author.id(),
                note.content() == null ? "" : note.content(),
                note.summary(),
                note.language(),
                visibility,
                note.sensitive(),
                note.inReplyTo(),
                published,
                // So that an Update older than this version can't replace it. Some servers send updated with the same time
                // as published, which isn't an edit.
                note.updated() != null && note.updated().isAfter(published) ? note.updated() : null));
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
