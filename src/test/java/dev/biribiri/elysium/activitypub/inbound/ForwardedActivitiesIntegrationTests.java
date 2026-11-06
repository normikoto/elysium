package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
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
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @MockitoBean
    private ActivityDocuments documents;

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

        forwarded.fetch(id, now());

        assertThat(storedBody()).contains("origin").doesNotContain("forwarded");
        assertThat(storedActor()).isEqualTo(actor);
        assertThat(inboundJobs()).isOne();
    }

    @Test
    void storesWhenTheForwardedCopyArrived() throws Exception {
        var arrivedAt = now().minusSeconds(60);
        serve(activity(id, actor, "origin"));

        forwarded.fetch(id, arrivedAt);

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
        forwarded.fetch(id, forwardedAt);
        followHandler.handle(stored.find(id).orElseThrow());

        assertThat(jdbc.sql("SELECT count(*) FROM follow WHERE followed_id = :id").param("id", followed.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void believesTheOriginAboutWhoTheActorIs() throws Exception {
        var other = "https://" + origin + "/users/b";
        serve(activity(id, other, "origin"));

        forwarded.fetch(id, now());

        assertThat(storedActor()).isEqualTo(other);
    }

    @Test
    void dropsWhatTheOriginServesUnderAnotherId() throws Exception {
        serve(activity("https://" + origin + "/activities/other", actor, "origin"));

        forwarded.fetch(id, now());

        assertThat(stored()).isZero();
    }

    @Test
    void dropsWhatTheOriginSaysIsFromAnotherServer() throws Exception {
        serve(activity(id, "https://elsewhere.test/users/a", "origin"));

        forwarded.fetch(id, now());

        assertThat(stored()).isZero();
    }

    @Test
    void dropsWhatTheOriginSaysHasSeveralActors() throws Exception {
        serve("{\"id\":\"" + id + "\",\"type\":\"Create\",\"actor\":[\"" + actor + "\",\"" + actor + "2\"]}");

        forwarded.fetch(id, now());

        assertThat(stored()).isZero();
    }

    @Test
    void dropsWhatTheOriginSaysIsFromAnActorWithNoHost() throws Exception {
        serve(activity(id, "urn:x:y", "origin"));

        forwarded.fetch(id, now());

        assertThat(stored()).isZero();
    }

    @Test
    void dropsAnActivityThatIsGoneOrRefused() throws Exception {
        when(documents.fetch(URI.create(id))).thenThrow(DocumentFetchException.gone("gone"));

        forwarded.fetch(id, now());

        assertThat(stored()).isZero();
    }

    @Test
    void triesAgainWhenTheOriginCannotBeReached() throws Exception {
        when(documents.fetch(URI.create(id))).thenThrow(DocumentFetchException.temporary("down", null));

        assertThatThrownBy(() -> forwarded.fetch(id, now())).isInstanceOf(IllegalStateException.class);
        assertThat(stored()).isZero();
    }

    @Test
    void doesNotFetchWhatItAlreadyHas() throws Exception {
        inbound.accept(new InboundActivity(id, actor, "Create", activity(id, actor, "direct")));

        assertThat(receive(activity(id, actor, "forwarded")).outcome()).isEqualTo(Inbox.Outcome.FORWARDED);
        forwarded.fetch(id, now());

        assertThat(forwardedJobs()).isZero();
        verify(documents, never()).fetch(any());
        assertThat(storedBody()).contains("direct");
    }

    @Test
    void doesNotFetchFromAServerBlockedSinceItWasQueued() throws Exception {
        receive(activity(id, actor, "forwarded"));
        blocks.create(origin, null);

        forwarded.fetch(id, now());

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
