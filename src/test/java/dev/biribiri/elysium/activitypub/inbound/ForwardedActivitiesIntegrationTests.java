package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Calls the inbox and the fetch directly with no worker running, so that each step can be seen. The origin is a mock, and
 * real servers are tested by the federation lab. The low limit is per server, and each test has its own servers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://forward.example",
        "elysium.jobs.worker-enabled=false",
        "elysium.federation.inbox-limits.activities-per-minute=3"
})
class ForwardedActivitiesIntegrationTests {

    @Autowired
    private Inbox inbox;
    @Autowired
    private ForwardedActivities forwarded;
    @Autowired
    private InboundActivities inbound;
    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private StoredActivities stored;
    @Autowired
    private FollowHandler followHandler;
    @Autowired
    private UndoHandler undoHandler;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private CreateHandler createHandler;
    @Autowired
    private StatusService statuses;
    @MockitoBean
    private ActivityDocuments documents;
    @MockitoSpyBean
    private TargetFetchTurns turns;

    private VerifiedSignature forwarder;
    private String origin;
    private String actor;
    private String id;

    @BeforeEach
    void setUp() {
        var forwarderUri = "https://f" + UUID.randomUUID() + ".test/users/f";
        forwarder = new VerifiedSignature(forwarderUri + "#main-key", URI.create(forwarderUri));
        origin = "o" + UUID.randomUUID() + ".test";
        actor = "https://" + origin + "/users/a";
        id = "https://" + origin + "/activities/" + UUID.randomUUID();
    }

    @Test
    void queuesAFetchAndStoresNothingUntilTheOriginAnswers() {
        var result = receive(activity(id, actor, "forwarded"));

        assertThat(result.outcome()).isEqualTo(Inbox.Outcome.FORWARDED);
        assertThat(stored()).isZero();
        assertThat(forwardedJobs()).isOne();
    }

    @Test
    void queuesOneFetchForRepeats() {
        receive(activity(id, actor, "forwarded"));
        receive(activity(id, actor, "forwarded"));

        assertThat(forwardedJobs()).isOne();
    }

    @Test
    void storesTheOriginsCopy() throws Exception {
        receive(activity(id, actor, "forwarded"));
        serve(activity(id, actor, "origin"));

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(storedBody()).contains("origin").doesNotContain("forwarded");
        assertThat(storedActor()).isEqualTo(actor);
        assertThat(inboundJobs()).isOne();
    }

    @Test
    void waitsForTheOriginsTurnWithoutFetching() throws Exception {
        var later = now().plusSeconds(30);
        doReturn(Optional.of(later)).when(turns).take(any(), any());
        var job = new ForwardedJob(id, now(), null, null);

        var handler = forwarded.fetch(job);

        verify(documents, never()).fetch(any());
        ExecutionComplete complete = mock();
        ExecutionOperations<ForwardedJob> operations = mock();
        handler.complete(complete, operations);
        verify(operations).reschedule(complete, later, job.withTurn(later));
    }

    @Test
    void usesATurnItWasGiven() throws Exception {
        serve(activity(id, actor, "origin"));

        forwarded.fetch(new ForwardedJob(id, now(), null, null, now().minusSeconds(1)));

        verify(turns, never()).take(any(), any());
        assertThat(storedBody()).contains("origin");
    }

    @Test
    void dropsAForwardWhoseTurnIsTooFarAway() throws Exception {
        doReturn(Optional.empty()).when(turns).take(any(), any());

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        verify(documents, never()).fetch(any());
        assertThat(stored()).isZero();
    }

    @Test
    void storesAForwardedReplyToAFollowedAccount() throws Exception {
        var followedHost = "p" + UUID.randomUUID() + ".test";
        var followedUri = URI.create("https://" + followedHost + "/users/p");
        var followed = actors.upsertRemote(new RemoteActorProfile(followedUri, "p", followedHost,
                URI.create(followedUri + "/inbox"), null, null, null, URI.create(followedUri + "/followers")));
        var follower = local();
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", followed.id())
                .param("activity", follower.uri() + "#follows/" + UUID.randomUUID())
                .update();
        var parent = followedUri + "/statuses/1";
        statuses.storeRemote(new RemoteStatus(URI.create(parent), null, followed.id(), "<p>Hi</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now()));
        actors.upsertRemote(new RemoteActorProfile(URI.create(actor), "a", origin, URI.create(actor + "/inbox"),
                null, null, null, URI.create(actor + "/followers")));
        var reply = "https://" + origin + "/notes/" + UUID.randomUUID();
        var create = """
                {"id":"%s","type":"Create","actor":"%s","object":{"id":"%s","type":"Note","attributedTo":"%s",
                 "to":["https://www.w3.org/ns/activitystreams#Public"],"cc":["%s/followers","%s"],"inReplyTo":"%s",
                 "content":"<p>Reply</p>"}}""".formatted(id, actor, reply, actor, actor, followedUri, parent);
        receive(create);
        serve(create);

        forwarded.fetch(new ForwardedJob(id, now(), null, null));
        createHandler.handle(stored.find(id).orElseThrow());

        assertThat(jdbc.sql("SELECT in_reply_to_account_id FROM status WHERE uri = :uri").param("uri", reply)
                .query(UUID.class).single()).isEqualTo(followed.id());
    }

    @Test
    void storesWhenTheForwardedCopyArrived() throws Exception {
        var arrivedAt = now().minusSeconds(60);
        serve(activity(id, actor, "origin"));

        forwarded.fetch(new ForwardedJob(id, arrivedAt, null, null));

        var activity = stored.find(id).orElseThrow();
        assertThat(activity.firstReceivedAt()).isEqualTo(arrivedAt);
        assertThat(activity.receivedAt()).isEqualTo(arrivedAt);
    }

    @Test
    void queuesTheFetchWithWhenTheForwardArrived() {
        var before = now();

        receive(activity(id, actor, "forwarded"));

        var data = jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8') FROM scheduled_tasks
                        WHERE task_name = 'forwarded-activity' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + id + "%")
                .query(String.class)
                .single();
        var queuedAt = Instant.parse(json.readTree(data).path("queuedAt").stringValue());
        assertThat(queuedAt).isBetween(before, now());
    }

    /**
     * The {@code Undo} arrives after the forwarded {@code Follow}, but before the {@code Follow} is fetched. It covers the
     * {@code Follow}, which would otherwise look newer and follow again.
     */
    @Test
    void anUndoThatArrivesWhileAForwardedFollowWaitsStillCoversIt() throws Exception {
        var followed = local();
        var followerDocument = "{\"id\":\"%s\",\"type\":\"Person\",\"preferredUsername\":\"a\",\"inbox\":\"%s/inbox\"}"
                .formatted(actor, actor);
        when(documents.fetch(URI.create(actor))).thenReturn(json.readTree(followerDocument));
        var follow = "{\"id\":\"%s\",\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}"
                .formatted(id, actor, followed.uri());
        var forwardedAt = now().minusSeconds(60);
        var undoId = "https://" + origin + "/activities/" + UUID.randomUUID();
        var undo = "{\"id\":\"%s\",\"type\":\"Undo\",\"actor\":\"%s\",\"object\":{\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}}"
                .formatted(undoId, actor, actor, followed.uri());

        undoHandler.handle(new InboundActivity(undoId, actor, "Undo", undo, forwardedAt.plusSeconds(30)));
        serve(follow);
        forwarded.fetch(new ForwardedJob(id, forwardedAt, null, null));
        followHandler.handle(stored.find(id).orElseThrow());

        assertThat(jdbc.sql("SELECT count(*) FROM follow WHERE followed_id = :id").param("id", followed.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void believesTheOriginAboutWhoTheActorIs() throws Exception {
        var other = "https://" + origin + "/users/b";
        serve(activity(id, other, "origin"));

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(storedActor()).isEqualTo(other);
    }

    @Test
    void dropsWhatTheOriginServesUnderAnotherId() throws Exception {
        serve(activity("https://" + origin + "/activities/other", actor, "origin"));

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(stored()).isZero();
    }

    @Test
    void dropsWhatTheOriginSaysIsFromAnotherServer() throws Exception {
        serve(activity(id, "https://elsewhere.test/users/a", "origin"));

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(stored()).isZero();
    }

    @Test
    void dropsWhatTheOriginSaysHasSeveralActors() throws Exception {
        serve("{\"id\":\"" + id + "\",\"type\":\"Create\",\"actor\":[\"" + actor + "\",\"" + actor + "2\"]}");

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(stored()).isZero();
    }

    @Test
    void dropsWhatTheOriginSaysIsFromAnActorWithNoHost() throws Exception {
        serve(activity(id, "urn:x:y", "origin"));

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(stored()).isZero();
    }

    @Test
    void dropsAnActivityThatIsGoneOrRefused() throws Exception {
        when(documents.fetch(URI.create(id))).thenThrow(DocumentFetchException.gone("gone"));

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(stored()).isZero();
    }

    @Test
    void triesAgainWhenTheOriginCannotBeReached() throws Exception {
        when(documents.fetch(URI.create(id))).thenThrow(DocumentFetchException.temporary("down", null));

        assertThatThrownBy(() -> forwarded.fetch(new ForwardedJob(id, now(), null, null))).isInstanceOf(IllegalStateException.class);
        assertThat(stored()).isZero();
    }

    @Test
    void doesNotFetchWhatItAlreadyHas() throws Exception {
        inbound.accept(new InboundActivity(id, actor, "Create", activity(id, actor, "direct")));

        assertThat(receive(activity(id, actor, "forwarded")).outcome()).isEqualTo(Inbox.Outcome.FORWARDED);
        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        assertThat(forwardedJobs()).isZero();
        verify(documents, never()).fetch(any());
        assertThat(storedBody()).contains("direct");
    }

    @Test
    void doesNotFetchFromAServerBlockedSinceItWasQueued() throws Exception {
        receive(activity(id, actor, "forwarded"));
        blocks.create(origin, null);

        forwarded.fetch(new ForwardedJob(id, now(), null, null));

        verify(documents, never()).fetch(any());
    }

    @Test
    void ignoresAnIdThatIsNotOnItsActorsServer() {
        var result = receive(activity("https://elsewhere.test/activities/1", actor, "forwarded"));

        assertThat(result.outcome()).isEqualTo(Inbox.Outcome.IGNORED);
        assertThat(forwardedJobs("https://elsewhere.test/activities/1")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"urn:x:y", "//elsewhere.test/users/a", "/users/a"})
    void ignoresAnActorWithNoSchemeOrHost(String actor) {
        assertThat(receive(activity(id, actor, "forwarded")).outcome()).isEqualTo(Inbox.Outcome.IGNORED);
        assertThat(forwardedJobs()).isZero();
    }

    /**
     * The forwarder is only passing it on, so it isn't told that its request was wrong.
     */
    @Test
    void ignoresAnIdThatIsNotAUri() {
        var result = receive(activity("https://" + origin + "/has space", actor, "forwarded"));

        assertThat(result.outcome()).isEqualTo(Inbox.Outcome.IGNORED);
    }

    @Test
    void ignoresAnActivityFromABlockedServer() {
        blocks.create(origin, null);

        assertThat(receive(activity(id, actor, "forwarded")).outcome()).isEqualTo(Inbox.Outcome.IGNORED);
        assertThat(forwardedJobs()).isZero();
    }

    @Test
    void ignoresAnActivityClaimingToBeFromThisServer() {
        var local = "https://forward.example/activities/" + UUID.randomUUID();

        var result = receive(activity(local, "https://forward.example/users/alice", "forwarded"));

        assertThat(result.outcome()).isEqualTo(Inbox.Outcome.IGNORED);
        assertThat(forwardedJobs(local)).isZero();
    }

    @Test
    void countsAgainstTheForwardersLimit() {
        for (var i = 0; i < 3; i++) {
            var next = "https://" + origin + "/activities/" + UUID.randomUUID();
            assertThat(receive(activity(next, actor, "forwarded")).outcome()).isEqualTo(Inbox.Outcome.FORWARDED);
        }

        var result = receive(activity(id, actor, "forwarded"));

        assertThat(result.outcome()).isEqualTo(Inbox.Outcome.RATE_LIMITED);
        assertThat(result.retryAfter()).isPositive();
        assertThat(forwardedJobs()).isZero();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Actor local() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, created -> {
        }).orElseThrow();
    }

    private Inbox.Result receive(String body) {
        return inbox.receive(forwarder, body.getBytes(StandardCharsets.UTF_8));
    }

    private void serve(String document) throws Exception {
        when(documents.fetch(URI.create(id))).thenReturn(json.readTree(document));
    }

    private static String activity(String id, String actor, String note) {
        return "{\"id\":\"%s\",\"type\":\"Create\",\"actor\":\"%s\",\"note\":\"%s\"}".formatted(id, actor, note);
    }

    private long stored() {
        return jdbc.sql("SELECT count(*) FROM inbound_activity WHERE activity_id = :id").param("id", id)
                .query(Long.class).single();
    }

    private String storedBody() {
        return jdbc.sql("SELECT body FROM inbound_activity WHERE activity_id = :id").param("id", id)
                .query(String.class).single();
    }

    private String storedActor() {
        return jdbc.sql("SELECT actor_uri FROM inbound_activity WHERE activity_id = :id").param("id", id)
                .query(String.class).single();
    }

    private long forwardedJobs() {
        return forwardedJobs(id);
    }

    private long forwardedJobs(String activityId) {
        return jobs("forwarded-activity", activityId);
    }

    private long inboundJobs() {
        return jobs("inbound-activity", id);
    }

    private long jobs(String task, String activityId) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = :task AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("task", task)
                .param("id", "%" + activityId + "%")
                .query(Long.class)
                .single();
    }
}
