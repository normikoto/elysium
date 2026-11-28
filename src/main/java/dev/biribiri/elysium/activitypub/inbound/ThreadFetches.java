package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.http.HostBusyException;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.RemoteThreadFetcher;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.ThreadRefresh;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
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
import java.util.function.Supplier;

/**
 * Fills in a thread that someone here is reading, as Mastodon does when a status's context is viewed, so that it shows the
 * parents and replies that nobody here was sent. A reply that arrives before its parent has its parents fetched too, as
 * Mastodon does, so that it doesn't sit alone until someone opens it.
 *
 * <p>Parents are fetched one at a time, each from the server its {@code inReplyTo} names, since only that server can say
 * what's there. Replies are listed by the {@code replies} collection of the status they answer, which is read from that
 * status's own server, as an outbox is in {@link OutboxBackfill}. Only the ID of each item is read, since an embedded reply
 * can be from another server, and each is fetched from its own server and kept only if its {@code inReplyTo} is the status
 * whose collection listed it, so that a collection can't add unrelated statuses to a thread. Every fetch is made on
 * another server's word, so each one takes a turn in {@link TargetFetchTurns}. Fetching stops at the limits of what a
 * context lists, since nothing past them would be shown.
 *
 * <p>A status whose {@code context} names a collection of its thread's posts, as FEP-f228 has it, has that read instead of
 * its replies, as the FEP says, since it lists the whole thread, which forums and their like don't reply to in a tree.
 * Whoever started the thread lists it, from any server, so only the ID of each item is read here too, and each post is
 * kept only if it names that collection as its {@code context} itself, or is linked by {@code inReplyTo} to what's stored
 * of the thread. A {@code context} that isn't a collection, as Pleroma's and Akkoma's aren't, has the replies read
 * instead.
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

    /**
     * Queued from a collection of a thread's posts, which lists the whole thread rather than what's under one status, so
     * this is as many as a context lists above and below a status.
     */
    static final int MAX_CONTEXT_POSTS = StatusService.MAX_ANCESTORS + StatusService.MAX_DESCENDANTS;

    /**
     * How long a post from a collection of a thread's posts waits for its parent, which that collection may list after it.
     */
    static final Duration PARENT_WAIT = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(ThreadFetches.class);

    private final StatusService statuses;
    private final RemoteStatuses remoteStatuses;
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
            RemoteStatuses remoteStatuses,
            ActorService actors,
            NoteStorage storage,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            InstanceProperties instance,
            JdbcClient jdbc,
            @Lazy SchedulerClient scheduler) {
        this.statuses = statuses;
        this.remoteStatuses = remoteStatuses;
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
        startSoon(statusId, true);
        return find(statusId).filter(ThreadRefresh::running);
    }

    /**
     * Fetches the parents of a note that was delivered and is stored, if what it replies to isn't stored. A note that's
     * deleted, or someone else's, has nothing fetched, and asking again is harmless, since a thread that was fetched
     * recently isn't fetched again. Its replies aren't fetched, since it's new enough to have none. This counts as a fetch
     * of its thread, so viewing it within {@link #REFETCH_AFTER} doesn't fetch its replies either, which would find
     * nothing yet. If the parent's own
     * {@code Create} is still waiting for its job, the parents job finds it stored if that job runs first, and otherwise
     * fetches it, which leaves the {@code Create} finding it taken. This makes no request itself.
     *
     * @param author the note's author, which has to be the activity's actor
     */
    void fetchParentsOf(RemoteNote note, URI author) {
        var parent = note.inReplyTo();
        // Checked before looking the reply up, since most notes aren't replies, or answer what's stored.
        if (parent == null || !isFetchable(parent) || remoteStatuses.isStored(parent)) {
            return;
        }
        actors.findByUri(author)
                .flatMap(found -> statuses.findStoredByUri(note.id(), found.id()))
                .ifPresent(reply -> startSoon(reply.id(), false));
    }

    private void startSoon(UUID statusId, boolean replies) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Most views come soon after another, and this saves them the write and row lock that starting takes.
        var recent = latest(statusId).filter(row -> row.startedAt().isAfter(now.minus(REFETCH_AFTER)));
        if (recent.isEmpty() && start(statusId, now)) {
            var group = group(statusId, now);
            Jobs.scheduleInGroup(scheduler, TASK, ThreadFetchJob.parents(statusId, now), group, "parents");
            if (replies) {
                Jobs.scheduleInGroup(scheduler, TASK, ThreadFetchJob.replies(statusId, now), group, "replies");
            }
        }
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

    @Bean
    static Task<ThreadFetchJob> threadFetch(ObjectProvider<ThreadFetches> threadFetches) {
        return Jobs.task(TASK, job -> threadFetches.getObject().run(job));
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
                case CONTEXT -> context(job);
                case CONTEXT_POST -> contextPost(job);
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
                || remoteStatuses.isStored(parent)) {
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
            if (own.isEmpty()) {
                return Jobs.done();
            }
            var context = job.contextId() == null ? contextOf(document.get()) : Optional.<URI>empty();
            return context.isPresent()
                    ? Jobs.rescheduleAt(Instant.now(), job.context(context.get()))
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
     * Reads the collection of the thread's posts, or one of its pages. The collection can be on any server, since a thread
     * is listed by whoever started it, but its pages have to be on the collection's own server. If the collection itself
     * can't be read, the replies are read instead, since some servers name a {@code context} that isn't one.
     */
    private CompletionHandler<ThreadFetchJob> context(ThreadFetchJob job) {
        var collection = URI.create(job.contextId());
        var target = URI.create(job.target());
        var first = target.equals(collection) && job.pages() == 0;
        Supplier<CompletionHandler<ThreadFetchJob>> unread =
                () -> first ? Jobs.rescheduleAt(Instant.now(), job.repliesWithoutContext()) : Jobs.done();
        if (!isFetchable(target)) {
            return unread.get();
        }
        var waiting = waitForTurn(target, job, unread);
        if (waiting.isPresent()) {
            return waiting.get();
        }
        Optional<JsonNode> document;
        try {
            document = fetch(target);
        } catch (HostBusyException e) {
            return Jobs.waitForHost(job, job::withTurn);
        } catch (IllegalStateException e) {
            // Retrying could leave the replies unread once the retries run out, and they're on another server.
            if (!first) {
                throw e;
            }
            log.debug("Reading the replies to {}, since {} couldn't be fetched: {}", job.statusId(), target,
                    e.getMessage());
            return unread.get();
        }
        if (first) {
            return document.filter(found -> ActorCollections.hasId(found, target, "OrderedCollection", "Collection"))
                    .map(found -> readContext(found, job, 1, job.depth()))
                    .orElseGet(() -> Jobs.rescheduleAt(Instant.now(), job.repliesWithoutContext()));
        }
        return document
                .filter(found -> ActorCollections.hasId(found, target, "OrderedCollectionPage", "CollectionPage"))
                .map(found -> readContextPage(found, job, job.pages() + 1, job.depth()))
                .orElseGet(Jobs::done);
    }

    /**
     * @param pages  how many pages have been read, this collection included
     * @param queued how many posts have been queued before it
     */
    private CompletionHandler<ThreadFetchJob> readContext(JsonNode collection, ThreadFetchJob job, int pages, int queued) {
        queued += queuePosts(collection, job, queued);
        if (pages >= MAX_PAGES || queued >= MAX_CONTEXT_POSTS) {
            return Jobs.done();
        }
        var first = collection.path("first");
        if (first.isObject()) {
            return readContextPage(first, job, pages + 1, queued);
        }
        return nextContextPage(first, job, pages, queued);
    }

    /**
     * @param pages  how many pages have been read, this one included
     * @param queued how many posts have been queued before it
     */
    private CompletionHandler<ThreadFetchJob> readContextPage(JsonNode page, ThreadFetchJob job, int pages, int queued) {
        queued += queuePosts(page, job, queued);
        if (pages >= MAX_PAGES || queued >= MAX_CONTEXT_POSTS) {
            return Jobs.done();
        }
        return nextContextPage(page.path("next"), job, pages, queued);
    }

    private CompletionHandler<ThreadFetchJob> nextContextPage(JsonNode next, ThreadFetchJob job, int pages, int queued) {
        var collection = URI.create(job.contextId());
        return Optional.ofNullable(ActivityJson.text(next))
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                .filter(page -> Inbox.isOnActorsServer(page, collection) && !page.toString().equals(job.target()))
                .map(page -> Jobs.rescheduleAt(Instant.now(), job.contextAt(page, pages, queued)))
                .orElseGet(Jobs::done);
    }

    /**
     * Queues a fetch of each post that a collection or page of the thread's posts lists, other than those stored already,
     * each in a job of its own, since each can be on a different server.
     *
     * @param queued how many posts have been queued before these
     * @return how many it queued
     */
    private int queuePosts(JsonNode page, ThreadFetchJob job, int queued) {
        var count = 0;
        for (var item : ActorCollections.items(page, MAX_CONTEXT_POSTS - queued)) {
            var id = Optional.ofNullable(ActivityJson.idOf(item))
                    .filter(InboundActivity::isStorableId)
                    .flatMap(ActivityObject::parseUri)
                    .filter(found -> isFetchable(found) && !remoteStatuses.isStored(found));
            if (id.isEmpty()) {
                continue;
            }
            // A post listed both in the collection and in its first page, or on two pages, only counts once.
            if (Jobs.scheduleInGroup(scheduler, TASK, job.contextPost(id.get()),
                    group(job.statusId(), job.queuedAt()), "context", id.get().toString())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Fetches a post that the collection of the thread's posts lists, from its own server. It's only stored if it says it's
     * in that collection too, or if it's linked to the thread by its {@code inReplyTo}, so that a collection can't have
     * statuses that have nothing to do with the thread stored. Its parent may be listed after it, with its job still to
     * run, so a post whose parent isn't stored yet waits for it once.
     */
    private CompletionHandler<ThreadFetchJob> contextPost(ThreadFetchJob job) {
        if (isFull(job)) {
            return Jobs.done();
        }
        var target = URI.create(job.target());
        if (!isFetchable(target) || remoteStatuses.isStored(target)) {
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
        var note = document.flatMap(found -> AttributedNote.of(target, found));
        if (note.isEmpty()) {
            return Jobs.done();
        }
        var parent = note.get().note().inReplyTo();
        var listed = contextOf(document.get()).filter(context -> context.toString().equals(job.contextId())).isPresent()
                || remoteStatuses.isInThreadOf(target, job.statusId())
                || parent != null && remoteStatuses.isInThreadOf(parent, job.statusId());
        if (!listed) {
            return parent != null && job.pages() < 1 && !remoteStatuses.isStored(parent)
                    ? Jobs.rescheduleAt(Instant.now().plus(PARENT_WAIT), job.waitedForParent())
                    : Jobs.done();
        }
        if (storage.storeInThread(note.get().note(), note.get().author()) == NoteStorage.Result.STORED) {
            stored(job);
        }
        return Jobs.done();
    }

    /**
     * The collection of the thread's posts that a note's {@code context} names, if it's one that could be fetched. Pleroma
     * and Akkoma name one that can't be, which is found out when it's fetched.
     */
    private Optional<URI> contextOf(JsonNode note) {
        return Optional.ofNullable(ActivityJson.idOf(note.path("context")))
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                .filter(this::isFetchable);
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
            if (id.isEmpty() || depth >= StatusService.MAX_DESCENDANT_DEPTH && remoteStatuses.isStored(id.get())) {
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
    boolean isFetchable(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().equalsIgnoreCase(instance.baseUrl().getHost())
                && !blocks.isBlocked(uri.getHost());
    }

    /**
     * @return what to do with the job if it has to wait for its turn, or has waited too long, or empty if it can fetch now
     */
    private Optional<CompletionHandler<ThreadFetchJob>> waitForTurn(URI target, ThreadFetchJob job) {
        return waitForTurn(target, job, Jobs::done);
    }

    /**
     * @param refused what to do instead if too many fetches are waiting for the target's server
     */
    private Optional<CompletionHandler<ThreadFetchJob>> waitForTurn(
            URI target, ThreadFetchJob job, Supplier<CompletionHandler<ThreadFetchJob>> refused) {
        var now = Instant.now();
        var turn = turns.take(target, sender(job), job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Not fetching {} for a thread, since too many fetches are waiting for its server", target);
            return Optional.of(refused.get());
        }
        if (turn.get().isAfter(now)) {
            return Optional.of(Jobs.waitUntil(turn.get(), job.withTurn(turn.get())));
        }
        return Optional.empty();
    }

    /**
     * The server of the status whose thread this is, which is what led to each URL in it, whether its thread was opened here
     * or one of its posts was sent here. A thread of this server's own status, or one whose status is gone, only counts
     * against the target's own limit.
     */
    private String sender(ThreadFetchJob job) {
        return statuses.findStored(job.statusId())
                .map(Status::uri)
                .filter(this::isFetchable)
                .map(TargetFetchTurns::senderOf)
                .orElse(null);
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
