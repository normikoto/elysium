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
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Stores a {@code Note} from another server as a status, when it's for a local account, through a follow or by addressing
 * it, or when it's a public or unlisted reply to a status of a local account or of one that's followed here, as Mastodon
 * keeps them. That last case is what a reply forwarded by the parent's server looks like. Notes that nobody here would see
 * aren't kept, which also stops any server from filling the database by delivering to the shared inbox.
 *
 * <p>Who a followers-only or direct note is shown to here comes from its mentions, so a local account only counts as
 * mentioned by one of those if the note is addressed to it. Otherwise a {@code Mention} tag alone would show it to anyone
 * here. A local account the note is addressed to without a tag is mentioned silently, which shows it the note without
 * listing it as mentioned, as Mastodon does. Accounts on other servers are only looked up among those stored, so that a
 * note can't make this server fetch them.
 *
 * <p>This is for a {@code Create}, and for an {@code Update} of a note that isn't stored yet. Both have to pass the same
 * checks, so that an {@code Update} can't store anything a {@code Create} couldn't.
 */
@Component
class NoteStorage {

    private static final Logger log = LoggerFactory.getLogger(NoteStorage.class);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final StatusService statuses;
    private final LocalRecipients recipients;
    private final DomainBlocks blocks;

    NoteStorage(
            ActorService actors,
            RemoteActors remoteActors,
            StatusService statuses,
            LocalRecipients recipients,
            DomainBlocks blocks) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.statuses = statuses;
        this.recipients = recipients;
        this.blocks = blocks;
    }

    /**
     * What {@link #store} did with a note.
     */
    enum Result {
        STORED,
        /** It isn't for anyone here, or its author can't post here. */
        IGNORED,
        /** A status with its URI is already stored, or was deleted. */
        TAKEN
    }

    /**
     * Blocks while fetching the author, so this mustn't be called inside a database transaction.
     *
     * @param document the activity's JSON
     * @param read     the activity's note, already checked to be its actor's by {@link ActorNotes}
     */
    Result store(InboundActivity activity, JsonNode document, ActorNotes.ActorNote read) {
        var actorUri = URI.create(activity.actorUri());
        var note = read.note();
        // Before anything that reads the author's followers collection, which is only known once the actor is stored. It
        // can make a request to another server.
        var author = remoteActors.fetchOrRefresh(actorUri);
        var visibility = visibility(note.addressing(), author);
        var forWhom = recipients.of(activity, document, read.fetched() ? note.addressing() : Addressing.NONE);
        // Followers-only replies stay out, since only the replier's followers could see them.
        if (forWhom.isEmpty() && !(note.inReplyTo() != null
                && (visibility == Visibility.PUBLIC || visibility == Visibility.UNLISTED)
                && recipients.repliesToSomeoneHere(note.inReplyTo()))) {
            log.debug("Ignoring {} {}, which isn't for any local account", activity.type(), activity.id());
            return Result.IGNORED;
        }
        // Fetching can take a while, and a block or suspension can have landed since the job started.
        if (blocks.isBlocked(actorUri.getHost()) || actors.isInactive(actorUri)) {
            return Result.IGNORED;
        }
        var published = note.published() == null ? activity.firstReceivedAt() : note.published();
        var stored = statuses.storeRemote(new RemoteStatus(
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
                // So that an Update older than this version can't replace it.
                note.editedAt(published, Instant.now()),
                mentions(note, visibility)));
        return stored.isPresent() ? Result.STORED : Result.TAKEN;
    }

    /**
     * @return each mentioned account, and whether it's mentioned silently
     */
    private Map<UUID, Boolean> mentions(RemoteNote note, Visibility visibility) {
        var mentions = new HashMap<UUID, Boolean>();
        var tagged = new HashSet<UUID>();
        actors.findLocalByUris(note.mentions()).forEach(local -> tagged.add(local.id()));
        var named = recipients.named(note.addressing());
        named.forEach(local -> mentions.put(local, !tagged.contains(local)));
        if (visibility == Visibility.PUBLIC || visibility == Visibility.UNLISTED) {
            tagged.forEach(local -> mentions.put(local, false));
        }
        for (var uri : note.mentions()) {
            actors.findByUri(uri)
                    .filter(actor -> actor.isActive() && !actors.isLocal(actor))
                    .ifPresent(remote -> mentions.put(remote.id(), false));
        }
        return mentions;
    }

    /**
     * Read from the note, as Mastodon reads it, rather than from its activity, since the note is what's stored. A note to
     * its author's followers is followers-only even if it also names other actors.
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
