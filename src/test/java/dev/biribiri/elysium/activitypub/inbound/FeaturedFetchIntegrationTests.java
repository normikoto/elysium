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
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reading pins from the {@code featured} collections of accounts on other servers. The worker doesn't run, so each test
 * runs the job itself, and the actor's server is a mock. Real servers are tested by the federation lab.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://featured.example", "elysium.jobs.worker-enabled=false"})
class FeaturedFetchIntegrationTests {
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired FeaturedFetch featured;
    @Autowired AcceptHandler accepts;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired StatusPinService pins;
    @Autowired FollowService follows;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoBean ActivityDocuments documents;
    @MockitoSpyBean TargetFetchTurns turns;

    private Actor alice;
    private Actor remote;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name IN ('featured-fetch', 'outbox-backfill')").update();
        var name = "ft" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        alice = actors.createLocalIfAbsent(name, actor -> {}).orElseThrow();
        remote = remote();
    }

    @Test
    void queuesOneFetchWhenAFollowIsAccepted() {
        follows.follow(alice, remote);
        var followUri = jdbc.sql("SELECT activity_uri FROM follow WHERE follower_id = :id").param("id", alice.id())
                .query(String.class).single();
        accepts.handle(accept(followUri));
        await().atMost(Duration.ofSeconds(10)).until(this::jobs, count -> count == 1);
    }

    @Test
    void replacesThePinsWithWhatTheCollectionListsInItsOrder() throws Exception {
        follow(alice, remote);
        var stored = remotePost();
        var unpinned = remotePost();
        pins.replaceRemote(remote.id(), Map.of(unpinned.id(), ago()), ago());
        var embedded = noteUri();
        serve(featuredUri(), collection(List.of(note(embedded, remote), stored.uri().toString())));

        runToTheEnd(job());

        assertThat(pinned()).containsExactly(statusId(embedded).orElseThrow(), stored.id());
        verify(turns).take(eq(featuredUri()), any(), any());
    }

    @Test
    void fetchesAndPinsWhatTheCollectionOnlyNames() throws Exception {
        follow(alice, remote);
        var named = noteUri();
        serve(featuredUri(), collection(List.of(named.toString())));
        serve(named, note(named, remote));

        runToTheEnd(job());
        assertThat(pinned()).isEmpty();
        assertThat(jobs()).isOne();
        var queued = jdbc.sql("SELECT task_instance FROM scheduled_tasks WHERE task_name = 'featured-fetch'")
                .query(String.class).single();
        assertThat(queued).isNotBlank();

        runToTheEnd(FeaturedFetchJob.ofNote(remote.id().toString(), now(), named, now()));
        assertThat(pinned()).containsExactly(statusId(named).orElseThrow());
    }

    @Test
    void ignoresWhatIsNotTheActorsOwnStatus() throws Exception {
        follow(alice, remote);
        var other = remote();
        var theirs = noteUri();
        var elsewhere = URI.create("https://elsewhere.test/statuses/" + UUID.randomUUID());
        serve(featuredUri(), collection(List.of(note(theirs, other), elsewhere.toString(),
                Map.of("type", "Hashtag", "name", "#tag"))));

        runToTheEnd(job());

        assertThat(pinned()).isEmpty();
        assertThat(statusId(theirs)).isEmpty();
        assertThat(jobs()).isZero();
        verify(documents, never()).fetch(elsewhere);
    }

    @Test
    void refusesACollectionWithAnotherId() throws Exception {
        follow(alice, remote);
        var collection = collection(List.of(note(noteUri(), remote)));
        collection.put("id", remote.uri() + "/collections/other");
        serve(featuredUri(), collection);

        runToTheEnd(job());

        assertThat(pinned()).isEmpty();
    }

    @Test
    void readsNothingForAnActorNobodyHereFollows() throws Exception {
        runToTheEnd(job());
        verify(documents, never()).fetch(any());
    }

    @Test
    void anActorWithoutACollectionHasNoPins() throws Exception {
        follow(alice, remote);
        var post = remotePost();
        pins.replaceRemote(remote.id(), Map.of(post.id(), ago()), ago());
        actors.upsertRemote(new RemoteActorProfile(remote.uri(), "someone", remote.domain(), remote.inboxUri(), null,
                null, null, null, null));

        runToTheEnd(job());

        assertThat(pinned()).isEmpty();
        verify(documents, never()).fetch(any());
    }

    private void runToTheEnd(FeaturedFetchJob job) {
        for (var runs = 0; job != null && runs < 3; runs++) {
            ExecutionComplete complete = mock();
            ExecutionOperations<FeaturedFetchJob> operations = mock();
            var rescheduled = new ArrayList<FeaturedFetchJob>();
            doAnswer(invocation -> rescheduled.add(invocation.getArgument(2)))
                    .when(operations).reschedule(any(), any(), any());
            featured.run(job).complete(complete, operations);
            job = rescheduled.isEmpty() ? null : rescheduled.getFirst();
        }
    }

    private FeaturedFetchJob job() {
        return FeaturedFetchJob.of(remote.id().toString(), now());
    }

    private List<UUID> pinned() {
        return pins.findPinned(remote.id(), Optional.empty()).stream().map(Status::id).toList();
    }

    private Status remotePost() {
        var id = statuses.storeRemote(new RemoteStatus(noteUri(), null, remote.id(), "<p>sent</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
        return statuses.findVisible(id, Optional.empty()).orElseThrow();
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
        var host = "ft" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"),
                URI.create(uri + "/outbox"), null, null, URI.create(uri + "/followers"),
                URI.create(uri + "/collections/featured")));
    }

    private URI featuredUri() {
        return URI.create(remote.uri() + "/collections/featured");
    }

    private InboundActivity accept(String followUri) {
        var id = remote.uri() + "/accepts/" + UUID.randomUUID();
        var body = Map.of("id", id, "type", "Accept", "actor", remote.uri().toString(), "object", followUri);
        return new InboundActivity(id, remote.uri().toString(), "Accept", json.writeValueAsString(body), Instant.now());
    }

    private URI noteUri() {
        return URI.create(remote.uri() + "/statuses/" + UUID.randomUUID());
    }

    /**
     * As Mastodon and Misskey serve it, with its items and no pages.
     */
    private Map<String, Object> collection(List<Object> items) {
        var collection = new LinkedHashMap<String, Object>();
        collection.put("id", featuredUri().toString());
        collection.put("type", "OrderedCollection");
        collection.put("totalItems", items.size());
        collection.put("orderedItems", items);
        return collection;
    }

    private Map<String, Object> note(URI id, Actor author) {
        var note = new LinkedHashMap<String, Object>();
        note.put("id", id.toString());
        note.put("type", "Note");
        note.put("attributedTo", author.uri().toString());
        note.put("content", "<p>pinned</p>");
        note.put("published", Instant.now().minusSeconds(3600).toString());
        note.put("to", List.of(PUBLIC));
        note.put("cc", List.of());
        return note;
    }

    private void serve(URI uri, Map<String, Object> document) throws DocumentFetchException {
        when(documents.fetch(uri)).thenReturn(json.readTree(json.writeValueAsString(document)));
    }

    private long jobs() {
        return jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'featured-fetch'")
                .query(Long.class).single();
    }

    private Optional<UUID> statusId(URI uri) {
        return jdbc.sql("SELECT id FROM status WHERE uri = :uri").param("uri", uri.toString()).query(UUID.class).optional();
    }

    private static Instant ago() {
        return now().minusSeconds(60);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
