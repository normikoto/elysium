package dev.biribiri.elysium.activitypub.outbound;

import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * Runs the real listener and scheduler against a real server on the loopback address. Any hostname reaches that server, so
 * each test uses its own hosts and its own author.
 */
@Import({TestcontainersConfiguration.class, StatusDeliveryIntegrationTests.Config.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class StatusDeliveryIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";
    private static final AtomicInteger next = new AtomicInteger();

    @Autowired
    private StatusService statuses;
    @Autowired
    private ActorService actors;
    @MockitoSpyBean
    private FollowService follows;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private EventRetries retries;

    private final Queue<Received> received = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private Actor author;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var host = exchange.getRequestHeaders().getFirst("Host").split(":")[0];
            received.add(new Received(
                    host,
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Signature"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        var username = "author" + next.incrementAndGet();
        actors.createLocalIfAbsent(username, actor -> {
        });
        author = actors.findLocalByUsername(username).orElseThrow();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsAPublicStatusToAFollower() {
        var host = newHost();
        follow(host, "bob", false);

        var status = post(Visibility.PUBLIC, null);

        var create = awaitCreate(host, status);
        assertThat(create.request().path()).isEqualTo("/users/bob/inbox");
        assertThat(create.request().signature()).contains("keyId=\"" + author.uri() + "#main-key\"");
        var body = create.body();
        assertThat(body.path("@context").path(1).path("sensitive").stringValue()).isEqualTo("as:sensitive");
        assertThat(body.path("id").stringValue()).isEqualTo(status.uri() + "#create");
        assertThat(body.path("type").stringValue()).isEqualTo("Create");
        assertThat(body.path("actor").stringValue()).isEqualTo(author.uri().toString());
        var note = body.path("object");
        assertThat(note.has("@context")).isFalse();
        assertThat(note.path("id").stringValue()).isEqualTo(status.uri().toString());
        assertThat(note.path("type").stringValue()).isEqualTo("Note");
        assertThat(note.path("attributedTo").stringValue()).isEqualTo(author.uri().toString());
        assertThat(note.path("content").stringValue()).isEqualTo(status.content());
        assertThat(body.path("published").stringValue()).isEqualTo(note.path("published").stringValue());
        assertThat(strings(body.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(body.path("cc"))).containsExactly(followers());
        assertThat(strings(note.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(note.path("cc"))).containsExactly(followers());
    }

    @Test
    void addressesAnUnlistedStatus() {
        var host = newHost();
        follow(host, "bob", false);

        var body = awaitCreate(host, post(Visibility.UNLISTED, null)).body();

        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).containsExactly(PUBLIC);
    }

    @Test
    void addressesAFollowersOnlyStatus() {
        var host = newHost();
        follow(host, "bob", false);

        var body = awaitCreate(host, post(Visibility.PRIVATE, null)).body();

        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).isEmpty();
    }

    @Test
    void aFollowersOnlyReplyLinksToTheAuthorsOwnFollowersOnlyParent() {
        var host = newHost();
        follow(host, "bob", false);
        var parent = post(Visibility.PRIVATE, null);

        var body = awaitCreate(host, post(Visibility.PRIVATE, parent.id())).body();

        assertThat(body.path("object").path("inReplyTo").stringValue()).isEqualTo(parent.uri().toString());
    }

    @Test
    void aPublicReplyDoesNotLinkToAFollowersOnlyParent() {
        var host = newHost();
        follow(host, "bob", false);
        var parent = post(Visibility.PRIVATE, null);

        var body = awaitCreate(host, post(Visibility.PUBLIC, parent.id())).body();

        assertThat(body.path("object").has("inReplyTo")).isFalse();
    }

    @Test
    void aFollowersOnlyReplyDoesNotLinkToADirectParent() {
        var host = newHost();
        follow(host, "bob", false);
        var parent = post(Visibility.DIRECT, null);

        var body = awaitCreate(host, post(Visibility.PRIVATE, parent.id())).body();

        assertThat(body.path("object").has("inReplyTo")).isFalse();
    }

    @Test
    void followersOnAServerShareItsSharedInbox() {
        var host = newHost();
        follow(host, "bob", true);
        follow(host, "carol", true);
        var other = newHost();
        follow(other, "dave", false);

        var status = post(Visibility.PUBLIC, null);

        awaitCreate(host, status);
        awaitCreate(other, status);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(host).size() == 1);
        assertThat(receivedBy(host).getFirst().path()).isEqualTo("/inbox");
        assertThat(receivedBy(other).getFirst().path()).isEqualTo("/users/dave/inbox");
    }

    @Test
    void sendsNothingForADirectStatus() {
        var host = newHost();
        follow(host, "bob", false);

        var status = post(Visibility.DIRECT, null);

        awaitNothingLeftFor(status);
        assertThat(receivedBy(host)).isEmpty();
        assertThat(stored(status)).isZero();
    }

    @Test
    void storesNothingWithoutFollowers() {
        var status = post(Visibility.PUBLIC, null);

        awaitNothingLeftFor(status);
        assertThat(stored(status)).isZero();
    }

    @Test
    void sendsNothingToAFormerFollower() {
        var host = newHost();
        var follower = follow(host, "bob", false);
        follows.undoPair(follower.toString(), author.id(), Instant.now());

        var status = post(Visibility.PUBLIC, null);

        awaitNothingLeftFor(status);
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void sendsNothingForARolledBackStatus() {
        var host = newHost();
        follow(host, "bob", false);

        var status = transactions.execute(transaction -> {
            var created = post(Visibility.PUBLIC, null);
            transaction.setRollbackOnly();
            return created;
        });

        assertThat(publications(status)).isZero();
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(host).isEmpty());
        assertThat(stored(status)).isZero();
    }

    @Test
    void retriesAFailedListener() {
        var host = newHost();
        follow(host, "bob", false);
        doThrow(new IllegalStateException("Expected by this test"))
                .doCallRealMethod()
                .when(follows).followerInboxes(author.id());

        var status = post(Visibility.PUBLIC, null);
        await().atMost(WAIT).until(() -> "FAILED".equals(publicationStatus(status)));
        assertThat(receivedBy(host)).isEmpty();

        backdatePublication(status);
        retries.retry();

        awaitCreate(host, status);
        awaitNothingLeftFor(status);
    }

    @Test
    void retriesARetryThatWasAbandoned() {
        var host = newHost();
        follow(host, "bob", false);
        doThrow(new IllegalStateException("Expected by this test"))
                .doCallRealMethod()
                .when(follows).followerInboxes(author.id());

        var status = post(Visibility.PUBLIC, null);
        await().atMost(WAIT).until(() -> "FAILED".equals(publicationStatus(status)));
        backdatePublication(status);
        jdbc.sql("""
                        UPDATE event_publication SET status = 'RESUBMITTED', last_resubmission_date = now() - interval '1 hour'
                        WHERE serialized_event LIKE :id
                        """)
                .param("id", "%" + status.id() + "%")
                .update();

        retries.retry();

        awaitCreate(host, status);
    }

    // Deleting

    @Test
    void sendsADeleteToAFollower() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);

        statuses.delete(status.id(), author.id());

        var delete = awaitDelete(host, status);
        assertThat(delete.request().path()).isEqualTo("/users/bob/inbox");
        assertThat(delete.request().signature()).contains("keyId=\"" + author.uri() + "#main-key\"");
        var body = delete.body();
        assertThat(body.path("@context").stringValue()).isEqualTo("https://www.w3.org/ns/activitystreams");
        assertThat(body.path("id").stringValue()).isEqualTo(status.uri() + "#delete");
        assertThat(body.path("type").stringValue()).isEqualTo("Delete");
        assertThat(body.path("actor").stringValue()).isEqualTo(author.uri().toString());
        assertThat(strings(body.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(body.path("cc"))).containsExactly(followers());
        var tombstone = body.path("object");
        assertThat(tombstone.has("@context")).isFalse();
        assertThat(tombstone.path("id").stringValue()).isEqualTo(status.uri().toString());
        assertThat(tombstone.path("type").stringValue()).isEqualTo("Tombstone");
        assertThat(tombstone.path("formerType").stringValue()).isEqualTo("Note");
    }

    @Test
    void addressesTheDeleteOfAnUnlistedStatus() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.UNLISTED, null);

        statuses.delete(status.id(), author.id());

        var body = awaitDelete(host, status).body();
        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).containsExactly(PUBLIC);
    }

    @Test
    void addressesTheDeleteOfAFollowersOnlyStatus() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PRIVATE, null);

        statuses.delete(status.id(), author.id());

        var body = awaitDelete(host, status).body();
        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).isEmpty();
    }

    @Test
    void followersOnAServerShareOneDelete() {
        var host = newHost();
        follow(host, "bob", true);
        follow(host, "carol", true);
        var status = post(Visibility.PUBLIC, null);

        statuses.delete(status.id(), author.id());

        assertThat(awaitDelete(host, status).request().path()).isEqualTo("/inbox");
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> deletesFor(host, status).size() == 1);
    }

    @Test
    void aLaterFollowerIsNotToldAboutADeletedFollowersOnlyStatus() {
        var earlier = newHost();
        follow(earlier, "bob", false);
        var status = post(Visibility.PRIVATE, null);
        awaitCreate(earlier, status);
        awaitNothingLeftFor(status);
        var later = newHost();
        follow(later, "carol", false);

        statuses.delete(status.id(), author.id());

        awaitDelete(earlier, status);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(later).isEmpty());
    }

    @Test
    void aLaterFollowerIsToldAboutADeletedPublicStatus() {
        var status = post(Visibility.PUBLIC, null);
        awaitNothingLeftFor(status);
        var later = newHost();
        follow(later, "carol", false);

        statuses.delete(status.id(), author.id());

        awaitDelete(later, status);
    }

    @Test
    void aFollowersOnlyStatusIsNotSentToSomeoneWhoFollowedAfterItWasPosted() {
        var earlier = newHost();
        follow(earlier, "bob", false);
        var later = newHost();
        // Runs in the listener, so this follow is after the status was posted, and before its audience is worked out.
        doAnswer(call -> {
            follow(later, "carol", false);
            return call.callRealMethod();
        }).doCallRealMethod().when(follows).followerInboxesAsOf(eq(author.id()), any());

        var status = post(Visibility.PRIVATE, null);

        awaitCreate(earlier, status);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(later).isEmpty());
    }

    @Test
    void aFollowerSentAFollowersOnlyStatusIsAlsoSentItsDelete() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PRIVATE, null);
        awaitCreate(host, status);

        statuses.delete(status.id(), author.id());

        awaitDelete(host, status);
    }

    @Test
    void sendsNothingForADeletedDirectStatus() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.DIRECT, null);

        statuses.delete(status.id(), author.id());

        awaitNothingLeftFor(status);
        assertThat(receivedBy(host)).isEmpty();
        assertThat(storedDelete(status)).isZero();
    }

    @Test
    void cancelsAnUnsentCreateEvenWithNoFollowersLeft() {
        var status = post(Visibility.PUBLIC, null);
        awaitNothingLeftFor(status);
        jdbc.sql("INSERT INTO outbound_activity (activity_id, actor_id, body) VALUES (:id, :actorId, '{}')")
                .param("id", status.uri() + "#create")
                .param("actorId", author.id())
                .update();

        statuses.delete(status.id(), author.id());

        awaitNothingLeftFor(status);
        assertThat(stored(status)).isZero();
        assertThat(storedDelete(status)).isZero();
    }

    @Test
    void sendsNothingForARolledBackDelete() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);
        awaitNothingLeftFor(status);

        transactions.executeWithoutResult(transaction -> {
            statuses.delete(status.id(), author.id());
            transaction.setRollbackOnly();
        });

        assertThat(statuses.findVisible(status.id(), Optional.empty())).isPresent();
        assertThat(statuses.findTombstone(status.id(), author.id())).isEmpty();
        assertThat(publications(status)).isZero();
        assertThat(storedDelete(status)).isZero();
    }

    @Test
    void retriesAFailedDeleteListener() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);
        awaitNothingLeftFor(status);
        doThrow(new IllegalStateException("Expected by this test"))
                .doCallRealMethod()
                .when(follows).followerInboxes(author.id());

        statuses.delete(status.id(), author.id());
        await().atMost(WAIT).until(() -> "FAILED".equals(publicationStatus(status)));

        backdatePublication(status);
        retries.retry();

        awaitDelete(host, status);
        awaitNothingLeftFor(status);
    }

    @Test
    void aDeleteWaitsForItsCreateToBeQueuedAndThenCancelsIt() {
        var host = newHost();
        follow(host, "bob", false);
        var posted = new CompletableFuture<Status>();
        var deleting = new CompletableFuture<CompletableFuture<?>>();
        var deleteWaited = new AtomicBoolean();
        // Only the Create's listener gets this. By then, the status is held until that listener commits.
        doAnswer(call -> {
            var status = posted.get(30, TimeUnit.SECONDS);
            var delete = CompletableFuture.runAsync(() -> statuses.delete(status.id(), author.id()));
            deleting.complete(delete);
            try {
                delete.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                deleteWaited.set(true);
            }
            return call.callRealMethod();
        }).doCallRealMethod().when(follows).followerInboxes(author.id());

        var status = post(Visibility.PUBLIC, null);
        posted.complete(status);

        await().atMost(WAIT).until(() -> deleting.isDone() && deleting.join().isDone());
        assertThat(deleteWaited).isTrue();
        awaitDelete(host, status);
        await().atMost(WAIT).until(() -> stored(status) == 0);
    }

    // Helpers

    private String newHost() {
        return "statuses" + next.incrementAndGet() + ".test";
    }

    private URI follow(String host, String username, boolean sharedInbox) {
        var domain = host + ":" + server.getAddress().getPort();
        var uri = URI.create("http://" + domain + "/users/" + username);
        var follower = actors.upsertRemote(new RemoteActorProfile(
                uri,
                username,
                domain,
                URI.create(uri + "/inbox"),
                null,
                sharedInbox ? URI.create("http://" + domain + "/inbox") : null,
                null));
        var now = Instant.now();
        transactions.executeWithoutResult(transaction -> follows.accept(
                follower.id(), uri, author.id(), uri + "#follow", new FollowService.Arrival(now, now)));
        return uri;
    }

    private Status post(Visibility visibility, UUID inReplyTo) {
        return statuses.create(author.id(), new NewStatus("Hello", null, null, visibility, false, inReplyTo));
    }

    private String followers() {
        return author.uri() + "/followers";
    }

    private Delivered awaitCreate(String host, Status status) {
        return awaitActivity(host, status.uri() + "#create");
    }

    private Delivered awaitDelete(String host, Status status) {
        return awaitActivity(host, status.uri() + "#delete");
    }

    private Delivered awaitActivity(String host, String activityId) {
        await().atMost(WAIT).until(() -> receivedBy(host).stream()
                .anyMatch(request -> request.body().contains(activityId)));
        var request = receivedBy(host).stream()
                .filter(candidate -> candidate.body().contains(activityId))
                .findFirst()
                .orElseThrow();
        return new Delivered(request, json.readTree(request.body()));
    }

    private List<Received> deletesFor(String host, Status status) {
        return receivedBy(host).stream().filter(request -> request.body().contains(status.uri() + "#delete")).toList();
    }

    private void awaitNothingLeftFor(Status status) {
        await().atMost(WAIT).until(() -> publications(status) == 0);
    }

    private List<Received> receivedBy(String host) {
        return received.stream().filter(request -> request.host().equals(host)).toList();
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::stringValue).toList();
    }

    private long stored(Status status) {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id")
                .param("id", status.uri() + "#create")
                .query(Long.class)
                .single();
    }

    private long storedDelete(Status status) {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id")
                .param("id", status.uri() + "#delete")
                .query(Long.class)
                .single();
    }

    private long publications(Status status) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + status.id() + "%")
                .query(Long.class)
                .single();
    }

    private String publicationStatus(Status status) {
        return jdbc.sql("SELECT status FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + status.id() + "%")
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void backdatePublication(Status status) {
        jdbc.sql("UPDATE event_publication SET publication_date = now() - interval '1 hour' WHERE serialized_event LIKE :id")
                .param("id", "%" + status.id() + "%")
                .update();
    }

    private record Received(String host, String path, String signature, String body) {
    }

    private record Delivered(Received request, JsonNode body) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        FederationHttpClient loopbackClient() {
            return LoopbackClients.create();
        }
    }
}
