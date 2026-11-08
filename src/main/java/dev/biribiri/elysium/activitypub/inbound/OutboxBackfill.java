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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Stores what a remote actor posted before anyone here followed them, from the first page of their outbox, once a local
 * account's follow of them is accepted. Without this, following someone shows nothing of theirs until they next post.
 *
 * <p>Only {@code Create}s by the actor of a public or unlisted {@code Note} of its own are read. The outbox and its page are
 * fetched from the actor's own server, which {@link RemoteActorDocument} requires of the outbox, and redirects aren't
 * followed, so a {@code Note} they embed is that server's word, as it would be if it were fetched. GoToSocial only names
 * each {@code Note} by its ID, so those are fetched, each in a job of its own, unless they're stored already. An
 * {@code Announce} is someone else's status, so it isn't read. Each fetch takes a turn in {@link TargetFetchTurns}, since
 * a follow is accepted on the other server's word.
 */
@Component
class OutboxBackfill {

    static final String TASK_NAME = "outbox-backfill";

    static final TaskDescriptor<OutboxBackfillJob> TASK = TaskDescriptor.of(TASK_NAME, OutboxBackfillJob.class);

    /**
     * Mastodon's pages have 20, and this server's 40.
     */
    static final int MAX_ITEMS = 40;

    private static final Logger log = LoggerFactory.getLogger(OutboxBackfill.class);

    private final ActorService actors;
    private final StatusService statuses;
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
            StatusService statuses,
            RemoteActors remoteActors,
            FollowService follows,
            NoteStorage storage,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            @Lazy SchedulerClient scheduler) {
        this.actors = actors;
        this.statuses = statuses;
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
                OutboxBackfillJob.of(actorId, Instant.now().truncatedTo(ChronoUnit.MICROS)), TASK_NAME, actorId);
    }

    /**
     * @return what to do with the job, which is to wait if its turn hasn't come yet, or to go on to the outbox's first page
     * @throws IllegalStateException if the actor's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<OutboxBackfillJob> run(OutboxBackfillJob job) {
        var note = Optional.ofNullable(job.note()).map(URI::create);
        // It can have arrived another way while this waited.
        if (note.filter(statuses::isStored).isPresent()) {
            return Jobs.done();
        }
        var uri = actors.findById(UUID.fromString(job.actorId()))
                .filter(found -> found.isActive() && !actors.isLocal(found))
                .map(Actor::uri);
        // Unfollowed while this waited, or never followed here at all, such as for a job an admin retried.
        if (uri.isEmpty() || blocks.isBlocked(uri.get().getHost()) || !follows.hasLocalFollowers(uri.get())) {
            return Jobs.done();
        }
        Actor actor;
        try {
            // Throws if the actor has been suspended or deleted, and refreshes its outbox.
            actor = remoteActors.fetchOrRefresh(uri.get());
        } catch (PermanentJobFailure e) {
            log.debug("Not backfilling {}: {}", uri.get(), e.getMessage());
            return Jobs.done();
        }
        var target = note.or(() -> Optional.ofNullable(job.page()).map(URI::create))
                .orElse(actor.outboxUri());
        if (target == null || !Inbox.isOnActorsServer(target, actor.uri())) {
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
        if (note.isPresent()) {
            RemoteNote.read(document.get())
                    .filter(found -> found.id().equals(target) && isActors(found, actor))
                    .ifPresent(found -> storage.storeBackfilled(found, actor));
            return Jobs.done();
        }
        if (job.page() != null) {
            if (hasId(document.get(), target, "OrderedCollectionPage", "CollectionPage")) {
                store(document.get(), actor, job);
            }
            return Jobs.done();
        }
        if (!hasId(document.get(), target, "OrderedCollection", "Collection")) {
            return Jobs.done();
        }
        var first = document.get().path("first");
        if (first.isObject()) {
            store(first, actor, job);
            return Jobs.done();
        }
        if (first.isMissingNode() || first.isNull()) {
            // A collection can list its items itself.
            store(document.get(), actor, job);
            return Jobs.done();
        }
        var page = idOnActorsServer(first, actor);
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
                && Arrays.stream(types).anyMatch(found::contains);
    }

    /**
     * Stores the notes the page embeds, and queues a fetch of each one it only names.
     */
    private void store(JsonNode page, Actor actor, OutboxBackfillJob job) {
        var items = page.has("orderedItems") ? page.path("orderedItems") : page.path("items");
        var listed = items.isArray() ? StreamSupport.stream(items.spliterator(), false).limit(MAX_ITEMS).toList()
                : List.of(items);
        var stored = 0;
        var queued = 0;
        for (var item : listed) {
            var object = createdObject(item, actor);
            if (object.isEmpty()) {
                continue;
            }
            if (object.get().isObject()) {
                var note = RemoteNote.read(object.get()).filter(found -> isActors(found, actor));
                if (note.isPresent() && storage.storeBackfilled(note.get(), actor) == NoteStorage.Result.STORED) {
                    stored++;
                }
                continue;
            }
            var named = idOnActorsServer(object.get(), actor).filter(id -> !statuses.isStored(id));
            if (named.isPresent()) {
                Jobs.schedule(scheduler, TASK, job.forNote(named.get()), TASK_NAME, job.actorId(), named.get().toString());
                queued++;
            }
        }
        log.debug("Backfilled {} statuses of {}, and queued fetches of {}", stored, actor.uri(), queued);
    }

    /**
     * @return the {@code object} of a {@code Create} by the actor, embedded or an ID
     */
    private static Optional<JsonNode> createdObject(JsonNode item, Actor actor) {
        if (!item.isObject() || !ActivityJson.types(item.path("type")).contains("Create")
                || !ActivityJson.ids(item.path("actor")).equals(List.of(actor.uri().toString()))) {
            return Optional.empty();
        }
        var object = item.path("object");
        if (object.isArray()) {
            object = object.size() == 1 ? object.get(0) : null;
        }
        return object == null || !(object.isObject() || object.isString()) ? Optional.empty() : Optional.of(object);
    }

    /**
     * The actor's server serves what it says is at these, so they're the only IDs that are fetched on its word.
     */
    private static Optional<URI> idOnActorsServer(JsonNode node, Actor actor) {
        return Optional.ofNullable(ActivityJson.text(node))
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                .filter(id -> Inbox.isOnActorsServer(id, actor.uri()));
    }

    /**
     * Whether the note is attributed to the actor alone, with an ID on its server that could be stored.
     */
    private static boolean isActors(RemoteNote note, Actor actor) {
        return note.attributedTo().equals(List.of(actor.uri().toString()))
                && InboundActivity.isStorableId(note.id().toString())
                && Inbox.isOnActorsServer(note.id(), actor.uri());
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
