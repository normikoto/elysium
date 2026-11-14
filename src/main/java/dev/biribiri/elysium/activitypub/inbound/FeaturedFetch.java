package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.FollowAccepted;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a remote actor's pins from its {@code featured} collection, as Mastodon and Misskey do, when a local account's
 * follow of it is accepted and when it sends an {@code Update} of itself. That's the only way to learn of pins made before,
 * and the only way at all for servers like GoToSocial, which don't send {@code Add}s. What the collection lists replaces
 * what was pinned.
 *
 * <p>The collection is read like an outbox in {@link OutboxBackfill}: from the actor's own server, as
 * {@link RemoteActorDocument} requires of it, with what it embeds taken as that server's word, and up to
 * {@link StatusPinService#MAX_REMOTE} items of its first page. A status it only names is fetched in a job of its own, unless
 * it's stored already. Only public and unlisted statuses are stored that way, since the fetch isn't made for anyone in
 * particular. Each fetch takes a turn in {@link TargetFetchTurns}.
 */
@Component
class FeaturedFetch {

    static final String TASK_NAME = "featured-fetch";

    static final TaskDescriptor<FeaturedFetchJob> TASK = TaskDescriptor.of(TASK_NAME, FeaturedFetchJob.class);

    private static final Logger log = LoggerFactory.getLogger(FeaturedFetch.class);

    private final ActorService actors;
    private final StatusService statuses;
    private final StatusPinService pins;
    private final RemoteActors remoteActors;
    private final FollowService follows;
    private final NoteStorage storage;
    private final ActivityDocuments documents;
    private final TargetFetchTurns turns;
    private final DomainBlocks blocks;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy, because the scheduler needs this job's task, which needs this
     */
    FeaturedFetch(
            ActorService actors,
            StatusService statuses,
            StatusPinService pins,
            RemoteActors remoteActors,
            FollowService follows,
            NoteStorage storage,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            @Lazy SchedulerClient scheduler) {
        this.actors = actors;
        this.statuses = statuses;
        this.pins = pins;
        this.remoteActors = remoteActors;
        this.follows = follows;
        this.storage = storage;
        this.documents = documents;
        this.turns = turns;
        this.blocks = blocks;
        this.scheduler = scheduler;
    }

    @ApplicationModuleListener
    public void on(FollowAccepted event) {
        queue(event.followedId());
    }

    /**
     * One job for each actor, so that several reasons to read the collection at once read it once.
     */
    void queue(UUID actorId) {
        Jobs.schedule(scheduler, TASK, FeaturedFetchJob.of(actorId.toString(), now()), TASK_NAME, actorId.toString());
    }

    /**
     * Fetches a status that an {@code Add} named, and pins it.
     *
     * @param pinnedAt when the {@code Add} arrived
     */
    void queueNote(UUID actorId, URI note, Instant pinnedAt) {
        Jobs.schedule(scheduler, TASK, FeaturedFetchJob.ofNote(actorId.toString(), now(), note, pinnedAt),
                TASK_NAME, actorId.toString(), note.toString());
    }

    /**
     * @return what to do with the job, which is to wait if its turn hasn't come yet, or to go on to the collection's first
     * page
     * @throws IllegalStateException if the actor's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<FeaturedFetchJob> run(FeaturedFetchJob job) {
        var actorId = UUID.fromString(job.actorId());
        var note = Optional.ofNullable(job.note()).map(URI::create);
        if (note.isPresent()) {
            // It can have arrived another way while this waited.
            var stored = statuses.findStoredByUri(note.get(), actorId);
            if (stored.isPresent()) {
                pins.receivePin(actorId, stored.get(), job.pinnedAt());
                return Jobs.done();
            }
        }
        var uri = actors.findById(actorId).filter(found -> found.isActive() && !actors.isLocal(found)).map(Actor::uri);
        // Unfollowed while this waited, or never followed here at all, such as for a job an admin retried.
        if (uri.isEmpty() || blocks.isBlocked(uri.get().getHost()) || !follows.hasLocalFollowers(uri.get())) {
            return Jobs.done();
        }
        Actor actor;
        try {
            // Throws if the actor has been suspended or deleted, and refreshes its featured collection.
            actor = remoteActors.fetchOrRefresh(uri.get());
        } catch (PermanentJobFailure e) {
            log.debug("Not reading the pins of {}: {}", uri.get(), e.getMessage());
            return Jobs.done();
        }
        var target = note.or(() -> Optional.ofNullable(job.page()).map(URI::create))
                .or(() -> actors.findFeaturedUri(actor.id()));
        if (target.isEmpty()) {
            // It no longer has pins to show, or never did.
            pins.replaceRemote(actor.id(), Map.of(), Instant.now());
            return Jobs.done();
        }
        if (!Inbox.isOnActorsServer(target.get(), actor.uri())) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(target.get(), job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Not reading the pins of {}, since too many fetches are waiting for its server", actor.uri());
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.rescheduleAt(turn.get(), job.withTurn(turn.get()));
        }
        var document = fetch(target.get());
        if (document.isEmpty()) {
            return Jobs.done();
        }
        if (note.isPresent()) {
            RemoteNote.read(document.get())
                    .filter(found -> found.id().equals(target.get()) && ActorCollections.isActors(found, actor))
                    .filter(found -> storage.storeBackfilled(found, actor) != NoteStorage.Result.IGNORED)
                    .flatMap(found -> statuses.findStoredByUri(found.id(), actor.id()))
                    .ifPresent(status -> pins.receivePin(actor.id(), status, job.pinnedAt()));
            return Jobs.done();
        }
        if (job.page() != null) {
            if (ActorCollections.hasId(document.get(), target.get(), "OrderedCollectionPage", "CollectionPage")) {
                replace(document.get(), actor, now);
            }
            return Jobs.done();
        }
        if (!ActorCollections.hasId(document.get(), target.get(), "OrderedCollection", "Collection")) {
            return Jobs.done();
        }
        var first = document.get().path("first");
        if (first.isObject()) {
            replace(first, actor, now);
            return Jobs.done();
        }
        if (first.isMissingNode() || first.isNull()) {
            // Mastodon and Misskey list the items in the collection itself.
            replace(document.get(), actor, now);
            return Jobs.done();
        }
        var page = ActorCollections.idOnActorsServer(first, actor);
        if (page.isEmpty()) {
            log.debug("Not reading the pins of {}, whose collection's first page isn't on its server", actor.uri());
            return Jobs.done();
        }
        // The page needs a turn of its own.
        return Jobs.rescheduleAt(now, job.forPage(page.get()));
    }

    /**
     * Pins what the page lists, the first item as the most recently pinned, and unpins anything else. A status it only names
     * and that isn't stored yet is pinned once its own job has fetched it.
     */
    private void replace(JsonNode page, Actor actor, Instant now) {
        var listed = new LinkedHashMap<UUID, Instant>();
        var queued = 0;
        var items = ActorCollections.items(page, StatusPinService.MAX_REMOTE);
        for (var i = 0; i < items.size(); i++) {
            var item = items.get(i);
            // Collections don't say when each was pinned, so they're spaced to keep the order they're listed in.
            var pinnedAt = now.truncatedTo(ChronoUnit.MICROS).minus(i, ChronoUnit.MICROS);
            Optional<URI> id;
            if (item.isObject()) {
                var embedded = RemoteNote.read(item).filter(found -> ActorCollections.isActors(found, actor));
                embedded.ifPresent(found -> storage.storeBackfilled(found, actor));
                id = embedded.map(RemoteNote::id);
            } else {
                id = ActorCollections.idOnActorsServer(item, actor);
            }
            if (id.isEmpty()) {
                continue;
            }
            var stored = statuses.findStoredByUri(id.get(), actor.id());
            if (stored.isPresent()) {
                listed.put(stored.get().id(), pinnedAt);
            } else if (!item.isObject()) {
                queueNote(actor.id(), id.get(), pinnedAt);
                queued++;
            }
        }
        pins.replaceRemote(actor.id(), listed, now);
        log.debug("Read {} pins of {}, and queued fetches of {}", listed.size(), actor.uri(), queued);
    }

    /**
     * @return the document, or empty if the actor's server refuses to serve it
     * @throws IllegalStateException if the server couldn't be reached, which is worth trying again
     */
    private Optional<JsonNode> fetch(URI url) {
        try {
            return Optional.of(documents.fetch(url));
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Not reading pins from {}, which couldn't be fetched: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
