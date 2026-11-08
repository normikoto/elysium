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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Stores what a remote actor posted before anyone here followed them, from the first page of their outbox, once a local
 * account's follow of them is accepted. Without this, following someone shows nothing of theirs until they next post.
 *
 * <p>Only {@code Create}s that embed a public or unlisted {@code Note} by the actor are read. The outbox and its page are
 * fetched from the actor's own server, which {@link RemoteActorDocument} requires of the outbox, and redirects aren't
 * followed, so what they embed is that server's word, as it would be if each {@code Note} were fetched. Nothing else is
 * fetched, so a page costs at most two requests. An ID-only item would cost a request each, and an {@code Announce} is
 * someone else's status. Each fetch takes a turn in {@link TargetFetchTurns}, since a follow is accepted on the other
 * server's word.
 */
@Component
class OutboxBackfill {

    static final TaskDescriptor<OutboxBackfillJob> TASK = TaskDescriptor.of("outbox-backfill", OutboxBackfillJob.class);

    /**
     * Mastodon's pages have 20, and this server's 40.
     */
    static final int MAX_ITEMS = 40;

    private static final Logger log = LoggerFactory.getLogger(OutboxBackfill.class);

    private final ActorService actors;
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
    OutboxBackfill(
            ActorService actors,
            RemoteActors remoteActors,
            FollowService follows,
            NoteStorage storage,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            @Lazy SchedulerClient scheduler) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.follows = follows;
        this.storage = storage;
        this.documents = documents;
        this.turns = turns;
        this.blocks = blocks;
        this.scheduler = scheduler;
    }

    /**
     * One job for each actor, so that several accounts following the same actor at once fetch its outbox once.
     */
    @ApplicationModuleListener
    public void on(FollowAccepted event) {
        var actorId = event.followedId().toString();
        Jobs.schedule(scheduler, TASK,
                new OutboxBackfillJob(actorId, Instant.now().truncatedTo(ChronoUnit.MICROS), null, null),
                "outbox-backfill", actorId);
    }

    /**
     * @return what to do with the job, which is to wait if its turn hasn't come yet, or to go on to the outbox's first page
     * @throws IllegalStateException if the actor's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<OutboxBackfillJob> run(OutboxBackfillJob job) {
        var stored = actors.findById(UUID.fromString(job.actorId()))
                .filter(actor -> actor.isActive() && !actors.isLocal(actor));
        // Unfollowed while this waited, or never followed here at all, such as for a job an admin retried.
        if (stored.isEmpty() || blocks.isBlocked(stored.get().uri().getHost())
                || !follows.hasLocalFollowers(stored.get().uri())) {
            return Jobs.done();
        }
        Actor actor;
        try {
            // Throws if the actor has been suspended or deleted, and refreshes its outbox.
            actor = remoteActors.fetchOrRefresh(stored.get().uri());
        } catch (PermanentJobFailure e) {
            log.debug("Not backfilling {}: {}", stored.get().uri(), e.getMessage());
            return Jobs.done();
        }
        var outbox = actor.outboxUri();
        var target = job.page() == null ? outbox : URI.create(job.page());
        if (outbox == null || !Inbox.isOnActorsServer(target, actor.uri())) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(target, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Not backfilling {}, since too many fetches are waiting for its server", actor.uri());
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.rescheduleAt(turn.get(), job.withTurn(turn.get()));
        }
        var document = fetch(target);
        if (document.isEmpty()) {
            return Jobs.done();
        }
        if (job.page() != null) {
            if (hasId(document.get(), target, "OrderedCollectionPage", "CollectionPage")) {
                store(document.get(), actor);
            }
            return Jobs.done();
        }
        if (!hasId(document.get(), target, "OrderedCollection", "Collection")) {
            return Jobs.done();
        }
        var first = document.get().path("first");
        if (first.isObject()) {
            store(first, actor);
            return Jobs.done();
        }
        if (first.isMissingNode() || first.isNull()) {
            // A collection can list its items itself.
            store(document.get(), actor);
            return Jobs.done();
        }
        var page = Optional.ofNullable(ActivityJson.text(first))
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                .filter(uri -> Inbox.isOnActorsServer(uri, actor.uri()));
        if (page.isEmpty()) {
            log.debug("Not backfilling {}, whose outbox's first page isn't on its server", actor.uri());
            return Jobs.done();
        }
        // The page needs a turn of its own.
        return Jobs.rescheduleAt(now, job.forPage(page.get()));
    }

    /**
     * @return whether the document is one of these types, with the ID it was fetched from, so that it's what the actor's
     * server serves at that URL rather than a copy of something else
     */
    private static boolean hasId(JsonNode document, URI fetched, String... types) {
        var found = ActivityJson.types(document.path("type"));
        return fetched.toString().equals(ActivityJson.text(document.path("id")))
                && List.of(types).stream().anyMatch(found::contains);
    }

    private void store(JsonNode page, Actor actor) {
        var items = page.has("orderedItems") ? page.path("orderedItems") : page.path("items");
        var listed = items.isArray() ? StreamSupport.stream(items.spliterator(), false).limit(MAX_ITEMS).toList()
                : List.of(items);
        var stored = 0;
        for (var item : listed) {
            var note = note(item, actor);
            if (note.isPresent() && storage.storeBackfilled(note.get(), actor) == NoteStorage.Result.STORED) {
                stored++;
            }
        }
        log.debug("Backfilled {} statuses of {}", stored, actor.uri());
    }

    /**
     * @return the note a {@code Create} by the actor embeds, if it's the actor's and could be stored
     */
    private static Optional<RemoteNote> note(JsonNode item, Actor actor) {
        var actorUri = actor.uri().toString();
        if (!item.isObject() || !ActivityJson.types(item.path("type")).contains("Create")
                || !ActivityJson.ids(item.path("actor")).equals(List.of(actorUri))) {
            return Optional.empty();
        }
        var object = item.path("object");
        if (object.isArray()) {
            object = object.size() == 1 ? object.get(0) : null;
        }
        if (object == null || !object.isObject()) {
            return Optional.empty();
        }
        return RemoteNote.read(object)
                .filter(note -> note.attributedTo().equals(List.of(actorUri)))
                .filter(note -> InboundActivity.isStorableId(note.id().toString())
                        && Inbox.isOnActorsServer(note.id(), actor.uri()));
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
            log.debug("Not backfilling from {}, which couldn't be fetched: {}", url, e.getMessage());
            return Optional.empty();
        }
    }
}
