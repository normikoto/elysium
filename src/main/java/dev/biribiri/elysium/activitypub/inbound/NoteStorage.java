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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stores a {@code Note} from another server as a status, when it's for a local account, through a follow or by addressing
 * it, or when it's a public or unlisted reply to a status of a local account or of one that's followed here, as Mastodon
 * keeps them. That last case is what a reply forwarded by the parent's server looks like. Notes that nobody here would see
 * aren't kept, which also stops any server from filling the database by delivering to the shared inbox.
 *
 * <p>Who a followers-only or direct note is shown to here comes from its mentions, so a local account only counts as
 * mentioned by one of those if the note or its activity is addressed to it. Otherwise a {@code Mention} tag alone would show
 * it to anyone here. A local account that's addressed without a tag is mentioned silently, which shows it the note without
 * listing it as mentioned, as Mastodon does. Accounts on other servers are only looked up among those stored, so that a
 * note can't make this server fetch them.
 *
 * <p>This is for a {@code Create}, and for an {@code Update} of a note that isn't stored yet. Both have to pass the same
 * checks, so that an {@code Update} can't store anything a {@code Create} couldn't. A note that a boost names is stored
 * through {@link #storeBoosted}, since who it's for comes from the boost.
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
        return save(note, author, visibility, activity.firstReceivedAt(),
                Addressing.ofActivity(document).with(note.addressing()), statuses::storeRemote);
    }

    /**
     * Stores a note that a boost names, fetched from its author's server. A boost can only show a note that anyone can see,
     * so a followers-only or direct one isn't kept, and nothing else decides who it's for. Blocks while fetching the author,
     * so this mustn't be called inside a database transaction.
     *
     * @param author the note's only {@code attributedTo}, already checked to be on the note's server
     */
    Result storeBoosted(RemoteNote note, URI author) {
        if (blocks.isBlocked(author.getHost())) {
            return Result.IGNORED;
        }
        return storeFetched(note, remoteActors.fetchOrRefresh(author), Instant.now(), statuses::storeRemote);
    }

    /**
     * Stores a public or unlisted note from its author's outbox. Like {@link #storeBoosted}, who it's for comes from the
     * follow that asked for it, not from its addressing, and the caller has to have checked that it's the author's. One
     * with no {@code published} is ignored, since it would sort as new.
     *
     * @param author already fetched or refreshed by the caller
     */
    Result storeBackfilled(RemoteNote note, Actor author) {
        if (note.published() == null) {
            return Result.IGNORED;
        }
        return storeFetched(note, author, note.published(), statuses::storeBackfilled);
    }

    /**
     * For a note fetched on its own rather than sent to someone here, which only anyone can be shown, so its addressing
     * decides nothing else.
     */
    private Result storeFetched(
            RemoteNote note, Actor author, Instant arrivedAt, Function<RemoteStatus, Optional<UUID>> store) {
        var visibility = visibility(note.addressing(), author);
        if (visibility != Visibility.PUBLIC && visibility != Visibility.UNLISTED) {
            log.debug("Ignoring fetched note {}, which not everyone can see", note.id());
            return Result.IGNORED;
        }
        if (blocks.isBlocked(author.uri().getHost()) || actors.isInactive(author.uri())) {
            return Result.IGNORED;
        }
        return save(note, author, visibility, arrivedAt, note.addressing(), store);
    }

    /**
     * @param arrivedAt when the note arrived, which stands in for a missing {@code published}
     * @param addressed the addressing that decides who it mentions
     * @param store     how it's stored, which decides how it sorts
     */
    private Result save(RemoteNote note, Actor author, Visibility visibility, Instant arrivedAt, Addressing addressed,
                        Function<RemoteStatus, Optional<UUID>> store) {
        var published = note.published() == null ? arrivedAt : note.published();
        var remote = new RemoteStatus(
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
                mentions(note, addressed, visibility),
                note.hashtags(),
                note.attachments());
        return store.apply(remote).isPresent() ? Result.STORED : Result.TAKEN;
    }

    /**
     * The local accounts named in the {@code Create} count as well as those named in its note, since the note's author
     * addressed both, and the {@code Create} is what decides that a note is for someone here. Otherwise a direct note sent
     * to a local account that only its {@code Create} names would be stored and shown to nobody.
     *
     * @param addressed the addressing of the activity and its note
     * @return each mentioned account, and whether it's mentioned silently
     */
    private Map<UUID, Boolean> mentions(RemoteNote note, Addressing addressed, Visibility visibility) {
        var mentions = new HashMap<UUID, Boolean>();
        var tagged = new HashSet<UUID>();
        var remote = new HashSet<UUID>();
        for (var actor : actors.findByUris(note.mentions())) {
            if (actor.isActive()) {
                (actors.isLocal(actor) ? tagged : remote).add(actor.id());
            }
        }
        var named = recipients.named(addressed);
        named.forEach(local -> mentions.put(local, !tagged.contains(local)));
        if (visibility == Visibility.PUBLIC || visibility == Visibility.UNLISTED) {
            tagged.forEach(local -> mentions.put(local, false));
        }
        remote.forEach(actor -> mentions.put(actor, false));
        return mentions;
    }

    /**
     * Read from the note, as Mastodon reads it, rather than from its activity, since the note is what's stored. A note to
     * its author's followers is followers-only even if it also names other actors. An {@code Announce} is read the same
     * way, since it's what a boost stores.
     */
    Visibility visibility(Addressing addressing, Actor author) {
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
