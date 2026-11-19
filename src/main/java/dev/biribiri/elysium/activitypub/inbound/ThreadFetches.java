package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.http.HostBusyException;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.RemoteThreadFetcher;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.ThreadRefresh;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Fills in a thread that someone here is reading, as Mastodon does when a status's context is viewed, so that it shows the
 * parents and replies that nobody here was sent.
 *
 * <p>Parents are fetched one at a time, each from the server its {@code inReplyTo} names, since only that server can say
 * what's there. Replies are listed by the {@code replies} collection of the status they answer, which is read from that
 * status's own server, as an outbox is in {@link OutboxBackfill}. Only the ID of each item is read, since an embedded reply
 * can be from another server, and each is fetched from its own server and kept only if its {@code inReplyTo} is the status
 * whose collection listed it, so that a collection can't add unrelated statuses to a thread. Every fetch is made on
 * another server's word, so each one takes a turn in {@link TargetFetchTurns}. Fetching stops at the limits of what a
 * context lists, since nothing past them would be shown.
 *
 * <p>Only public and unlisted statuses are stored, as for any status fetched on its own. When a thread was last fetched is
 * stored, so that every node waits as long before fetching it again. Its jobs are one group in {@link Jobs#scheduleInGroup},
 * so a client can be told whether the fetch is still running, as Mastodon's async refreshes do, without counting jobs as
 * they finish, which a job that runs again after succeeding would throw off.
 */
@Service
class ThreadFetches implements RemoteThreadFetcher {

    static final String TASK_NAME = "thread-fetch";

    static final TaskDescriptor<ThreadFetchJob> TASK = TaskDescriptor.of(TASK_NAME, ThreadFetchJob.class);

    /**
     * As on Mastodon, so that a thread that's read often doesn't keep being fetched. A fetch also counts as finished after
     * this long, even if some of its jobs are still waiting or failing, so that clients stop checking on it.
     */
    static final Duration REFETCH_AFTER = Duration.ofMinutes(15);

    /**
     * Read from each page of a replies collection. Mastodon's pages have 5, and a context lists 60 descendants at most.
     */
    static final int MAX_ITEMS = 20;

    /**
     * Read from each replies collection, which bounds what a server whose collection never ends can have fetched.
     */
    static final int MAX_PAGES = 10;

    private static final Logger log = LoggerFactory.getLogger(ThreadFetches.class);

    private final StatusService statuses;
    private final ActorService actors;
    private final NoteStorage storage;
    private final ActivityDocuments documents;
    private final TargetFetchTurns turns;
    private final DomainBlocks blocks;
    private final InstanceProperties instance;
    private final JdbcClient jdbc;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy, because the scheduler needs this job's task, which needs this
     */
    ThreadFetches(
            StatusService statuses,
            ActorService actors,
            NoteStorage storage,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            InstanceProperties instance,
            JdbcClient jdbc,
            @Lazy SchedulerClient scheduler) {
        this.statuses = statuses;
        this.actors = actors;
        this.storage = storage;
        this.documents = documents;
        this.turns = turns;
        this.blocks = blocks;
        this.instance = instance;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Override
    public Optional<ThreadRefresh> fetchSoon(UUID statusId) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Most views come soon after another, and this saves them the write and row lock that starting takes.
        var recent = latest(statusId).filter(row -> row.startedAt().isAfter(now.minus(REFETCH_AFTER)));
        if (recent.isEmpty() && start(statusId, now)) {
            var group = group(statusId, now);
            Jobs.scheduleInGroup(scheduler, TASK, ThreadFetchJob.parents(statusId, now), group, "parents");
            Jobs.scheduleInGroup(scheduler, TASK, ThreadFetchJob.replies(statusId, now), group, "replies");
        }
        return find(statusId).filter(ThreadRefresh::running);
    }

    /**
     * Keeps finding a fetch once it's counted as finished, until the job cleanup removes it, so that a client checking on
     * it is told it's finished rather than that it's gone.
     */
    @Override
    public Optional<ThreadRefresh> find(UUID statusId) {
        var after = Instant.now().minus(REFETCH_AFTER);
        return latest(statusId).map(row -> new ThreadRefresh(
                statusId,
                row.startedAt().isAfter(after) && Jobs.isGroupQueued(jdbc, TASK, group(statusId, row.startedAt())),
                row.resultCount()));
    }

    private Optional<RefreshRow> latest(UUID statusId) {
        return jdbc.sql("SELECT started_at, result_count FROM thread_refresh WHERE status_id = :id")
                .param("id", statusId)
                .query((rs, row) -> new RefreshRow(
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(), rs.getInt("result_count")))
                .optional();
    }

    private record RefreshRow(Instant startedAt, int resultCount) {
    }

    /**
     * Each fetch of a thread is a group of its own, so that jobs of an earlier one that are still waiting or failing
     * neither keep a later one running nor stop its jobs from being queued under the same IDs.
     */
    private static String group(UUID statusId, Instant startedAt) {
        return statusId + "@" + ChronoUnit.MICROS.between(Instant.EPOCH, startedAt);
    }

    /**
     * @return whether this starts a fetch, which it doesn't if one started recently, or the status is gone
     */
    private boolean start(UUID statusId, Instant now) {
        try {
            return jdbc.sql("""
                            INSERT INTO thread_refresh (status_id, started_at)
                            SELECT id, :now FROM status WHERE id = :id
                            ON CONFLICT (status_id) DO UPDATE SET started_at = :now, result_count = 0
                            WHERE thread_refresh.started_at <= :before
                            RETURNING status_id
                            """)
                    .param("id", statusId)
                    .param("now", at(now))
                    .param("before", at(now.minus(REFETCH_AFTER)))
                    .query(UUID.class)
                    .optional()
                    .isPresent();
        } catch (DataIntegrityViolationException e) {
            // Deleted since it was read.
            return false;
        }
    }

    /**
     * Counts a status stored for a thread, for clients deciding whether it's worth asking for the thread again.
     */
    private void stored(ThreadFetchJob job) {
        jdbc.sql("""
                        UPDATE thread_refresh SET result_count = result_count + 1
                        WHERE status_id = :id AND started_at = :startedAt
                        """)
                .param("id", job.statusId())
                .param("startedAt", at(job.queuedAt()))
                .update();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * @return what to do with the job, which is to wait if its turn hasn't come yet, or to go on to the next parent or page
     * @throws IllegalStateException if a server couldn't be reached, which is worth trying again
     */
    CompletionHandler<ThreadFetchJob> run(ThreadFetchJob job) {
        // A later fetch of the thread has started, which does everything this would, or it's so old that its row is gone.
        if (latest(job.statusId()).filter(row -> row.startedAt().equals(job.queuedAt())).isEmpty()) {
            return Jobs.done();
        }
        try {
            return switch (job.step()) {
                case PARENTS -> parents(job);
                case REPLIES -> replies(job);
                case REPLY -> reply(job);
            };
        } catch (PermanentJobFailure e) {
            // Such as an author who's been suspended here, which isn't worth parking the job for.
            log.debug("Not filling in the thread of {}: {}", job.statusId(), e.getMessage());
            return Jobs.done();
        }
    }

    private CompletionHandler<ThreadFetchJob> parents(ThreadFetchJob job) {
        var earliest = statuses.findEarliestAncestor(job.statusId());
        // A parent that's stored but not found is by someone who's been suspended, or is hidden in some other way.
        if (earliest.isEmpty() || earliest.get().inReplyToId() != null) {
            return Jobs.done();
        }
        var parent = earliest.get().inReplyToUri();
        // The same one again means the last fetch didn't store it, so there's nothing more to find above it.
        if (parent == null || parent.toString().equals(job.target()) || !isFetchable(parent)
                || statuses.isStored(parent)) {
            return Jobs.done();
        }
        var waiting = waitForTurn(parent, job);
        if (waiting.isPresent()) {
            return waiting.get();
        }
        Optional<JsonNode> fetched;
        try {
            fetched = fetch(parent);
        } catch (HostBusyException e) {
            return Jobs.waitForHost(job, job::withTurn);
        }
        var note = fetched.flatMap(document -> AttributedNote.of(parent, document));
        if (note.isEmpty() || storage.storeInThread(note.get().note(), note.get().author()) != NoteStorage.Result.STORED) {
            return Jobs.done();
        }
        stored(job);
        return Jobs.rescheduleAt(Instant.now(), job.afterParent(parent));
    }

    private CompletionHandler<ThreadFetchJob> replies(ThreadFetchJob job) {
        if (isFull(job)) {
            return Jobs.done();
        }
        var parent = statuses.findStored(job.parentId()).filter(found -> found.visibility().isOpen());
        var author = parent.flatMap(found -> actors.findById(found.actorId()))
                .filter(found -> found.isActive() && !actors.isLocal(found));
        // A local status's replies are sent here, so there's nothing to fetch.
        if (author.isEmpty()) {
            return Jobs.done();
        }
        var target = job.target() == null ? parent.get().uri() : URI.create(job.target());
        if (!Inbox.isOnActorsServer(target, parent.get().uri()) || !isFetchable(target)) {
            return Jobs.done();
        }
        var waiting = waitForTurn(target, job);
        if (waiting.isPresent()) {
            return waiting.get();
        }
        Optional<JsonNode> document;
        try {
            document = fetch(target);
        } catch (HostBusyException e) {
            return Jobs.waitForHost(job, job::withTurn);
        }
        if (document.isEmpty()) {
            return Jobs.done();
        }
        if (job.target() == null) {
            var own = AttributedNote.of(target, document.get())
                    .filter(found -> found.author().equals(author.get().uri()));
            return own.isEmpty()
                    ? Jobs.done()
                    : readReplies(document.get().path("replies"), parent.get(), job, job.depth(), job.pages());
        }
        if (ActorCollections.hasId(document.get(), target, "OrderedCollection", "Collection")) {
            // Counted as a page, so that a collection whose first is itself, or another collection, can't loop forever.
            return readCollection(document.get(), parent.get(), job, job.depth(), job.pages() + 1);
        }
        if (ActorCollections.hasId(document.get(), target, "OrderedCollectionPage", "CollectionPage")) {
            return readPage(document.get(), parent.get(), job, job.depth(), job.pages() + 1);
        }
        return Jobs.done();
    }

    private CompletionHandler<ThreadFetchJob> reply(ThreadFetchJob job) {
        if (isFull(job)) {
            return Jobs.done();
        }
        var parent = statuses.findStored(job.parentId());
        var target = URI.create(job.target());
        if (parent.isEmpty() || !isFetchable(target)) {
            return Jobs.done();
        }
        var waiting = waitForTurn(target, job);
        if (waiting.isPresent()) {
            return waiting.get();
        }
        Optional<JsonNode> document;
        try {
            document = fetch(target);
        } catch (HostBusyException e) {
            return Jobs.waitForHost(job, job::withTurn);
        }
        var note = document.flatMap(found -> AttributedNote.of(target, found))
                .filter(found -> parent.get().uri().equals(found.note().inReplyTo()));
        if (note.isEmpty()) {
            return Jobs.done();
        }
        // One that's stored already is still read for its own replies.
        if (storage.storeInThread(note.get().note(), note.get().author()) == NoteStorage.Result.STORED) {
            stored(job);
        }
        if (job.depth() >= StatusService.MAX_DESCENDANT_DEPTH) {
            return Jobs.done();
        }
        var reply = actors.findByUri(note.get().author())
                .filter(Actor::isActive)
                .flatMap(author -> statuses.findStoredByUri(note.get().note().id(), author.id()))
                .filter(found -> found.visibility().isOpen() && parent.get().id().equals(found.inReplyToId()));
        return reply.isEmpty()
                ? Jobs.done()
                : readReplies(document.get().path("replies"), reply.get(), job, job.depth() + 1, 0);
    }

    /**
     * A job that held a turn checked before it took one, and a thread fills up slowly enough that the few seconds since
     * don't matter, so it isn't worth walking the thread again.
     */
    private boolean isFull(ThreadFetchJob job) {
        return job.turn() == null && statuses.hasFullThread(job.statusId());
    }

    /**
     * Reads a note's {@code replies}, which can be embedded, or an ID on the note's server.
     *
     * @param depth how many replies down from the job's status the replies are
     */
    private CompletionHandler<ThreadFetchJob> readReplies(
            JsonNode replies, Status parent, ThreadFetchJob job, int depth, int pages) {
        if (replies.isObject()) {
            return readCollection(replies, parent, job, depth, pages);
        }
        return idOnServerOf(replies, parent)
                .map(collection -> Jobs.rescheduleAt(Instant.now(), job.repliesAt(parent.id(), depth, collection, pages)))
                .orElseGet(Jobs::done);
    }

    private CompletionHandler<ThreadFetchJob> readCollection(
            JsonNode collection, Status parent, ThreadFetchJob job, int depth, int pages) {
        // A collection can list its items itself.
        queueReplies(collection, parent, job, depth);
        if (pages >= MAX_PAGES) {
            return Jobs.done();
        }
        var first = collection.path("first");
        if (first.isObject()) {
            return readPage(first, parent, job, depth, pages + 1);
        }
        return idOnServerOf(first, parent)
                .map(page -> Jobs.rescheduleAt(Instant.now(), job.repliesAt(parent.id(), depth, page, pages)))
                .orElseGet(Jobs::done);
    }

    /**
     * @param pages how many pages have been read, this one included
     */
    private CompletionHandler<ThreadFetchJob> readPage(
            JsonNode page, Status parent, ThreadFetchJob job, int depth, int pages) {
        queueReplies(page, parent, job, depth);
        if (pages >= MAX_PAGES) {
            return Jobs.done();
        }
        return idOnServerOf(page.path("next"), parent)
                .filter(next -> !next.toString().equals(job.target()))
                .map(next -> Jobs.rescheduleAt(Instant.now(), job.repliesAt(parent.id(), depth, next, pages)))
                .orElseGet(Jobs::done);
    }

    /**
     * Queues a fetch of each reply that a collection or page lists, each in a job of its own, since each can be on a
     * different server.
     */
    private void queueReplies(JsonNode page, Status parent, ThreadFetchJob job, int depth) {
        for (var item : ActorCollections.items(page, MAX_ITEMS)) {
            var id = Optional.ofNullable(ActivityJson.idOf(item))
                    .filter(InboundActivity::isStorableId)
                    .flatMap(ActivityObject::parseUri)
                    .filter(found -> !found.equals(parent.uri()) && isFetchable(found));
            // A reply that's stored already is only fetched for its own replies, which wouldn't be shown this deep.
            if (id.isEmpty() || depth >= StatusService.MAX_DESCENDANT_DEPTH && statuses.isStored(id.get())) {
                continue;
            }
            // Keyed by its parent too, so that a collection listing it under the wrong parent can't keep the right one
            // from queuing it.
            Jobs.scheduleInGroup(scheduler, TASK, job.reply(parent.id(), depth, id.get()),
                    group(job.statusId(), job.queuedAt()), parent.id().toString(), id.get().toString());
        }
    }

    /**
     * The status's server serves its replies collection, so that's the only place it's read from.
     */
    private static Optional<URI> idOnServerOf(JsonNode node, Status status) {
        return Optional.ofNullable(ActivityJson.text(node))
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                .filter(id -> Inbox.isOnActorsServer(id, status.uri()));
    }

    /**
     * Whether a status could be fetched from here, which this server's own and a blocked server's can't.
     */
    private boolean isFetchable(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().equalsIgnoreCase(instance.baseUrl().getHost())
                && !blocks.isBlocked(uri.getHost());
    }

    /**
     * @return what to do with the job if it has to wait for its turn, or has waited too long, or empty if it can fetch now
     */
    private Optional<CompletionHandler<ThreadFetchJob>> waitForTurn(URI target, ThreadFetchJob job) {
        var now = Instant.now();
        var turn = turns.take(target, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Not fetching {} for a thread, since too many fetches are waiting for its server", target);
            return Optional.of(Jobs.done());
        }
        if (turn.get().isAfter(now)) {
            return Optional.of(Jobs.waitUntil(turn.get(), job.withTurn(turn.get())));
        }
        return Optional.empty();
    }

    /**
     * @return the document, or empty if its server refuses to serve it
     * @throws IllegalStateException if the server couldn't be reached, which is worth trying again
     */
    private Optional<JsonNode> fetch(URI url) {
        try {
            return Optional.of(documents.fetch(url));
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Not filling in a thread from {}, which couldn't be fetched: {}", url, e.getMessage());
            return Optional.empty();
        }
    }
}
