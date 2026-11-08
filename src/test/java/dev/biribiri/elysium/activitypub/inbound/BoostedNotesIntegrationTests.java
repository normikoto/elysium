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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boosts of statuses from other servers. The worker doesn't run, so each test runs the fetch job itself, and the status's
 * server is a mock. Real servers are tested by the federation lab.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://remote-boosts.example", "elysium.jobs.worker-enabled=false"})
class BoostedNotesIntegrationTests {
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired AnnounceHandler handler;
    @Autowired UndoHandler undos;
    @Autowired Boosts boosts;
    @Autowired InboundActivities inbound;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired FollowService follows;
    @Autowired DomainBlocks blocks;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoBean ActivityDocuments documents;
    @MockitoSpyBean TargetFetchTurns turns;

    private Actor alice;
    private Actor booster;
    private Actor author;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = 'boosted-note-fetch'").update();
        alice = actors.findLocalByUsername("rbalice")
                .orElseGet(() -> actors.createLocalIfAbsent("rbalice", actor -> {}).orElseThrow());
        booster = remote();
        author = remote();
    }

    @Test
    void onlyQueuesAFetchForABoostThatIsForSomeoneHere() {
        var note = noteUri(author);
        handler.handle(accepted(announce(booster, note, "nobody")));
        assertThat(jobs()).isZero();

        follow(alice, booster);
        var announce = accepted(announce(booster, note, "followed"));
        handler.handle(announce);
        handler.handle(announce);
        assertThat(jobs()).isOne();

        var named = remote();
        handler.handle(accepted(announce(named, noteUri(author), "named", alice.uri().toString())));
        assertThat(jobs()).isEqualTo(2);
    }

    @Test
    void fetchesTheBoostedStatusFromItsServerAndStoresBoth() throws Exception {
        follow(alice, booster);
        var note = noteUri(author);
        serve(note, note(note, author.uri(), List.of(PUBLIC)));
        var announce = accepted(announce(booster, note, "one"));
        handler.handle(announce);
        verify(documents, never()).fetch(any());

        boosts.run(job(announce));

        var statusId = statusId(note).orElseThrow();
        assertThat(jdbc.sql("SELECT actor_id FROM status WHERE id = :id").param("id", statusId)
                .query(UUID.class).single()).isEqualTo(author.id());
        assertThat(boostOf(statusId)).isOne();
        var home = statuses.findHome(alice.id(), follows.followedIds(alice.id()), new StatusPage(20, null, null, null));
        assertThat(home).anyMatch(status -> statusId.equals(status.reblogOfId()));
        verify(documents).fetch(note);
    }

    @Test
    void storesABoostOfAStoredStatusWithoutFetching() throws Exception {
        follow(alice, booster);
        var note = noteUri(author);
        var statusId = statuses.storeRemote(new RemoteStatus(note, null, author.id(), "<p>stored</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();

        handler.handle(accepted(announce(booster, note, "stored")));

        assertThat(boostOf(statusId)).isOne();
        assertThat(jobs()).isZero();
        verify(documents, never()).fetch(any());
    }

    @Test
    void ignoresWhatItsServerDoesNotShowAsOnePublicStatusOfItsOwn() throws Exception {
        follow(alice, booster);
        var otherServer = remote();
        var cases = List.<Map<String, Object>>of(
                note(URI.create(author.uri() + "/elsewhere"), author.uri(), List.of(PUBLIC)),
                note(null, otherServer.uri(), List.of(PUBLIC)),
                note(null, author.uri(), List.of(author.uri() + "/followers")),
                withAuthors(note(null, author.uri(), List.of(PUBLIC)), List.of(author.uri(), URI.create(author.uri() + "2"))));
        var index = 0;
        for (var served : cases) {
            var note = noteUri(author);
            if (served.get("id") == null) served.put("id", note.toString());
            serve(note, served);
            var announce = accepted(announce(booster, note, "case" + index++));
            handler.handle(announce);
            boosts.run(job(announce));
            assertThat(statusId(note)).as(served.toString()).isEmpty();
        }
    }

    @Test
    void neverFetchesFromABlockedDomainOrForAWithdrawnBoost() throws Exception {
        follow(alice, booster);
        var blockedAuthor = remote();
        var blocked = accepted(announce(booster, noteUri(blockedAuthor), "blocked"));
        handler.handle(blocked);
        blocks.create(blockedAuthor.domain(), null);

        var undone = accepted(announce(booster, noteUri(author), "undone"));
        handler.handle(undone);
        undos.handle(new InboundActivity(booster.uri() + "/undo/" + UUID.randomUUID(), booster.uri().toString(), "Undo",
                json.writeValueAsString(Map.of("id", booster.uri() + "/undo", "type", "Undo", "actor", booster.uri(),
                        "object", undone.id())), Instant.now()));

        boosts.run(job(blocked));
        boosts.run(job(undone));
        // Once it's been undone, a repeat isn't queued either.
        handler.handle(undone);

        verify(documents, never()).fetch(any());
    }

    @Test
    void waitsForItsServersTurnWithoutFetching() throws Exception {
        follow(alice, booster);
        var announce = accepted(announce(booster, noteUri(author), "wait"));
        var later = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        doReturn(Optional.of(later)).when(turns).take(any(), any(), any());
        var job = job(announce);

        var completion = boosts.run(job);

        verify(documents, never()).fetch(any());
        ExecutionComplete complete = mock();
        ExecutionOperations<BoostedNoteJob> operations = mock();
        completion.complete(complete, operations);
        verify(operations).reschedule(complete, later, job.withTurn(later));
    }

    @Test
    void triesAgainWhenItsServerCannotBeReached() throws Exception {
        follow(alice, booster);
        var note = noteUri(author);
        when(documents.fetch(note)).thenThrow(DocumentFetchException.temporary("Timed out", null));
        var announce = accepted(announce(booster, note, "down"));

        assertThatThrownBy(() -> boosts.run(job(announce))).isInstanceOf(IllegalStateException.class);
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
        var host = "rb" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"), null, null,
                null, URI.create(uri + "/followers")));
    }

    private static URI noteUri(Actor author) {
        return URI.create(author.uri() + "/statuses/" + UUID.randomUUID());
    }

    private InboundActivity announce(Actor actor, URI note, String suffix, String... also) {
        var id = actor.uri() + "/announces/" + suffix + "/" + UUID.randomUUID();
        var to = new java.util.ArrayList<Object>(List.of(PUBLIC));
        to.addAll(List.of(also));
        var body = Map.of("id", id, "type", "Announce", "actor", actor.uri(), "object", note,
                "to", to, "cc", List.of(actor.uri() + "/followers"));
        return new InboundActivity(id, actor.uri().toString(), "Announce", json.writeValueAsString(body), Instant.now());
    }

    private InboundActivity accepted(InboundActivity activity) {
        inbound.accept(activity);
        return activity;
    }

    private static Map<String, Object> note(URI id, URI attributedTo, List<String> to) {
        var note = new LinkedHashMap<String, Object>();
        note.put("@context", "https://www.w3.org/ns/activitystreams");
        note.put("id", id == null ? null : id.toString());
        note.put("type", "Note");
        note.put("attributedTo", attributedTo.toString());
        note.put("content", "<p>boosted</p>");
        note.put("published", Instant.now().minusSeconds(60).toString());
        note.put("to", to);
        return note;
    }

    private static Map<String, Object> withAuthors(Map<String, Object> note, List<URI> authors) {
        note.put("attributedTo", authors.stream().map(URI::toString).toList());
        return note;
    }

    private void serve(URI uri, Map<String, Object> document) throws DocumentFetchException {
        when(documents.fetch(uri)).thenReturn(json.readTree(json.writeValueAsString(document)));
    }

    private static BoostedNoteJob job(InboundActivity announce) {
        return new BoostedNoteJob(announce.id(), Instant.now().truncatedTo(ChronoUnit.MICROS), null);
    }

    private long jobs() {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks WHERE task_name = 'boosted-note-fetch'
                        """)
                .query(Long.class).single();
    }

    private Optional<UUID> statusId(URI uri) {
        return jdbc.sql("SELECT id FROM status WHERE uri = :uri").param("uri", uri.toString()).query(UUID.class).optional();
    }

    private long boostOf(UUID statusId) {
        return jdbc.sql("SELECT count(*) FROM status WHERE reblog_of_id = :id").param("id", statusId)
                .query(Long.class).single();
    }
}
