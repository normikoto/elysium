package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backfilling from the outboxes of accounts on other servers. The worker doesn't run, so each test runs the job itself,
 * and the actor's server is a mock. Real servers are tested by the federation lab.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://backfill.example", "elysium.jobs.worker-enabled=false"})
class OutboxBackfillIntegrationTests {
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired OutboxBackfill backfill;
    @Autowired AcceptHandler accepts;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired FollowService follows;
    @Autowired DomainBlocks blocks;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoBean ActivityDocuments documents;
    @MockitoSpyBean TargetFetchTurns turns;

    private Actor alice;
    private Actor remote;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = 'outbox-backfill'").update();
        var name = "bf" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        alice = actors.createLocalIfAbsent(name, actor -> {}).orElseThrow();
        remote = remote();
    }

    @Test
    void queuesOneBackfillWhenAFollowIsAccepted() {
        follows.follow(alice, remote);
        var followUri = jdbc.sql("SELECT activity_uri FROM follow WHERE follower_id = :id").param("id", alice.id())
                .query(String.class).single();
        assertThat(jobs()).isZero();

        accepts.handle(accept(followUri));
        await().atMost(Duration.ofSeconds(10)).until(this::jobs, count -> count == 1);
        // Once it's accepted, a repeat changes nothing.
        accepts.handle(accept(followUri));
        assertThat(jobs()).isOne();
    }

    @Test
    void storesPublicAndUnlistedNotesFromTheFirstPageInTheOrderTheyWerePosted() throws Exception {
        follow(alice, remote);
        var arrived = statuses.storeRemote(new RemoteStatus(noteUri(), null, remote.id(), "<p>sent</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
        var older = noteUri();
        var old = noteUri();
        var unlisted = noteUri();
        var page = URI.create(remote.outboxUri() + "?page=true");
        serve(remote.outboxUri(), collection(page.toString()));
        serve(page, page(page, List.of(
                create(note(older, List.of(PUBLIC), List.of(), Instant.parse("2025-02-01T00:00:00Z"))),
                create(note(old, List.of(PUBLIC), List.of(), Instant.parse("2025-01-01T00:00:00Z"))),
                create(note(unlisted, List.of(remote.uri() + "/followers"), List.of(PUBLIC),
                        Instant.parse("2024-12-01T00:00:00Z"))))));

        runToTheEnd(job());

        var olderId = statusId(older).orElseThrow();
        var oldId = statusId(old).orElseThrow();
        assertThat(statusId(unlisted)).isPresent();
        assertThat(olderId.compareTo(oldId)).isPositive();
        // Older than what was already here, so clients polling for new statuses aren't given them.
        assertThat(olderId.compareTo(arrived)).isNegative();
        var home = statuses.findHome(alice.id(), follows.followedIds(alice.id()), new StatusPage(40, null, null, null));
        assertThat(home).extracting(status -> status.uri()).contains(older, old, unlisted);
    }

    @Test
    void readsAnEmbeddedFirstPageWithoutFetchingIt() throws Exception {
        follow(alice, remote);
        var note = noteUri();
        var collection = collection(null);
        collection.put("first", page(URI.create(remote.outboxUri() + "?page=1"),
                List.of(create(note(note, List.of(PUBLIC), List.of(), Instant.now().minusSeconds(3600))))));
        serve(remote.outboxUri(), collection);

        runToTheEnd(job());

        assertThat(statusId(note)).isPresent();
        verify(documents).fetch(remote.outboxUri());
    }

    @Test
    void ignoresWhatIsNotAPublicNoteOfTheActorsOwn() throws Exception {
        follow(alice, remote);
        var other = remote();
        var otherServers = URI.create(other.uri() + "/statuses/" + UUID.randomUUID());
        var someoneElses = noteUri();
        var forFollowers = noteUri();
        var undated = noteUri();
        var announced = noteUri();
        var onlyAnId = noteUri();
        var notForMe = noteUri();
        var someoneElsesCreate = create(note(notForMe, List.of(PUBLIC), List.of(), Instant.now().minusSeconds(60)));
        someoneElsesCreate.put("actor", other.uri().toString());
        var undatedNote = note(undated, List.of(PUBLIC), List.of(), null);
        var page = URI.create(remote.outboxUri() + "?page=true");
        serve(remote.outboxUri(), collection(page.toString()));
        serve(page, page(page, List.of(
                create(note(otherServers, List.of(PUBLIC), List.of(), Instant.now().minusSeconds(60))),
                create(withAuthor(note(someoneElses, List.of(PUBLIC), List.of(), Instant.now().minusSeconds(60)), other)),
                create(note(forFollowers, List.of(remote.uri() + "/followers"), List.of(), Instant.now().minusSeconds(60))),
                create(undatedNote),
                Map.of("id", remote.uri() + "/announces/1", "type", "Announce", "actor", remote.uri().toString(),
                        "object", announced.toString(), "to", List.of(PUBLIC)),
                Map.of("id", onlyAnId + "#create", "type", "Create", "actor", remote.uri().toString(),
                        "object", onlyAnId.toString()),
                someoneElsesCreate)));

        runToTheEnd(job());

        for (var uri : List.of(otherServers, someoneElses, forFollowers, undated, announced, onlyAnId, notForMe)) {
            assertThat(statusId(uri)).as(uri.toString()).isEmpty();
        }
        verify(documents, never()).fetch(announced);
        verify(documents, never()).fetch(onlyAnId);
    }

    @Test
    void doesNotNotifyTheAccountsAnOldStatusMentions() throws Exception {
        follow(alice, remote);
        var note = note(noteUri(), List.of(PUBLIC), List.of(), Instant.now().minusSeconds(86400));
        note.put("tag", List.of(Map.of("type", "Mention", "href", alice.uri().toString())));
        serve(remote.outboxUri(), withItems(collection(null), List.of(create(note))));

        runToTheEnd(job());

        var statusId = statusId(URI.create((String) note.get("id"))).orElseThrow();
        assertThat(jdbc.sql("SELECT count(*) FROM status_mention WHERE status_id = :id").param("id", statusId)
                .query(Long.class).single()).isOne();
        // Listeners would have run by now, if anything had been published.
        Thread.sleep(500);
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_notification WHERE status_id = :id").param("id", statusId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void neverFetchesWhenNobodyFollowsOrTheDomainIsBlocked() throws Exception {
        backfill.run(job());

        var blocked = remote();
        follow(alice, blocked);
        blocks.create(blocked.domain(), null);
        backfill.run(new OutboxBackfillJob(blocked.id().toString(), Instant.now(), null, null));

        verify(documents, never()).fetch(any());
    }

    @Test
    void neverFetchesAFirstPageOnAnotherServer() throws Exception {
        follow(alice, remote);
        var elsewhere = "https://elsewhere-" + UUID.randomUUID() + ".test/outbox?page=true";
        serve(remote.outboxUri(), collection(elsewhere));

        runToTheEnd(job());

        verify(documents, never()).fetch(URI.create(elsewhere));
    }

    @Test
    void ignoresAnOutboxServedUnderAnotherId() throws Exception {
        follow(alice, remote);
        var note = noteUri();
        var collection = withItems(collection(null),
                List.of(create(note(note, List.of(PUBLIC), List.of(), Instant.now().minusSeconds(60)))));
        collection.put("id", remote.uri() + "/elsewhere");
        serve(remote.outboxUri(), collection);

        runToTheEnd(job());

        assertThat(statusId(note)).isEmpty();
    }

    @Test
    void waitsForItsServersTurnWithoutFetching() throws Exception {
        follow(alice, remote);
        var later = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        doReturn(Optional.of(later)).when(turns).take(any(), any(), any());
        var job = job();

        var completion = backfill.run(job);

        verify(documents, never()).fetch(any());
        ExecutionComplete complete = mock();
        ExecutionOperations<OutboxBackfillJob> operations = mock();
        completion.complete(complete, operations);
        verify(operations).reschedule(complete, later, job.withTurn(later));
    }

    @Test
    void triesAgainWhenItsServerCannotBeReached() throws Exception {
        follow(alice, remote);
        when(documents.fetch(remote.outboxUri())).thenThrow(DocumentFetchException.temporary("Timed out", null));

        assertThatThrownBy(() -> backfill.run(job())).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Runs the job, and again with whatever data it reschedules itself with, as the scheduler would.
     */
    private void runToTheEnd(OutboxBackfillJob job) {
        for (var runs = 0; job != null && runs < 3; runs++) {
            ExecutionComplete complete = mock();
            ExecutionOperations<OutboxBackfillJob> operations = mock();
            var rescheduled = new ArrayList<OutboxBackfillJob>();
            doAnswer(invocation -> rescheduled.add(invocation.getArgument(2)))
                    .when(operations).reschedule(any(), any(), any());
            backfill.run(job).complete(complete, operations);
            job = rescheduled.isEmpty() ? null : rescheduled.getFirst();
        }
    }

    private OutboxBackfillJob job() {
        return new OutboxBackfillJob(remote.id().toString(), Instant.now().truncatedTo(ChronoUnit.MICROS), null, null);
    }

    /**
     * Accepted straight away, as the other server would have.
     */
    private void follow(Actor follower, Actor followed) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri, activity_at)
                        VALUES (:id, :follower, :followed, 'accepted', :activity, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", followed.id())
                .param("activity", "https://example.test/activities/" + UUID.randomUUID())
                .update();
    }

    private Actor remote() {
        var host = "bf" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"),
                URI.create(uri + "/outbox"), null, null, URI.create(uri + "/followers")));
    }

    private InboundActivity accept(String followUri) {
        var id = remote.uri() + "/accepts/" + UUID.randomUUID();
        var body = Map.of("id", id, "type", "Accept", "actor", remote.uri().toString(), "object", followUri);
        return new InboundActivity(id, remote.uri().toString(), "Accept", json.writeValueAsString(body), Instant.now());
    }

    private URI noteUri() {
        return URI.create(remote.uri() + "/statuses/" + UUID.randomUUID());
    }

    private Map<String, Object> collection(String first) {
        var collection = new LinkedHashMap<String, Object>();
        collection.put("@context", "https://www.w3.org/ns/activitystreams");
        collection.put("id", remote.outboxUri().toString());
        collection.put("type", "OrderedCollection");
        collection.put("totalItems", 3);
        if (first != null) collection.put("first", first);
        return collection;
    }

    private static Map<String, Object> withItems(Map<String, Object> collection, List<Map<String, Object>> items) {
        collection.put("orderedItems", items);
        return collection;
    }

    private Map<String, Object> page(URI id, List<Map<String, Object>> items) {
        var page = new LinkedHashMap<String, Object>();
        page.put("id", id.toString());
        page.put("type", "OrderedCollectionPage");
        page.put("partOf", remote.outboxUri().toString());
        page.put("orderedItems", items);
        return page;
    }

    private Map<String, Object> create(Map<String, Object> note) {
        var create = new LinkedHashMap<String, Object>();
        create.put("id", note.get("id") + "#create");
        create.put("type", "Create");
        create.put("actor", remote.uri().toString());
        create.put("object", note);
        return create;
    }

    private Map<String, Object> note(URI id, List<String> to, List<String> cc, Instant published) {
        var note = new LinkedHashMap<String, Object>();
        note.put("id", id.toString());
        note.put("type", "Note");
        note.put("attributedTo", remote.uri().toString());
        note.put("content", "<p>from before</p>");
        if (published != null) note.put("published", published.toString());
        note.put("to", to);
        note.put("cc", cc);
        return note;
    }

    private static Map<String, Object> withAuthor(Map<String, Object> note, Actor author) {
        note.put("attributedTo", author.uri().toString());
        return note;
    }

    private void serve(URI uri, Map<String, Object> document) throws DocumentFetchException {
        when(documents.fetch(uri)).thenReturn(json.readTree(json.writeValueAsString(document)));
    }

    private long jobs() {
        return jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'outbox-backfill'")
                .query(Long.class).single();
    }

    private Optional<UUID> statusId(URI uri) {
        return jdbc.sql("SELECT id FROM status WHERE uri = :uri").param("uri", uri.toString()).query(UUID.class).optional();
    }
}
