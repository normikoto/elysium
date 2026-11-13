package dev.biribiri.elysium.activitypub.outbound;

import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.RemotePoll;
import dev.biribiri.elysium.content.RemoteStatus;
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
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real listeners and scheduler against a server on the loopback address, as
 * {@link StatusDeliveryIntegrationTests} does.
 */
@Import({TestcontainersConfiguration.class, PollDeliveryIntegrationTests.Config.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class PollDeliveryIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final AtomicInteger next = new AtomicInteger();

    @Autowired
    private StatusService statuses;
    @Autowired
    private PollService polls;
    @Autowired
    private PollDeliveries deliveries;
    @Autowired
    private ActorService actors;
    @Autowired
    private FollowService follows;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;

    private final Queue<Received> received = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private Actor author;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var host = exchange.getRequestHeaders().getFirst("Host").split(":")[0];
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(host, exchange.getRequestURI().getPath(), body));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        var username = "pollster" + next.incrementAndGet();
        actors.createLocalIfAbsent(username, actor -> {
        });
        author = actors.findLocalByUsername(username).orElseThrow();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsALocalPollAsAQuestion() {
        var host = newHost();
        follow(host, "bob");

        var status = poll(true);

        var question = awaitActivity(host, status.uri() + "#create").path("object");
        assertThat(question.path("type").stringValue()).isEqualTo("Question");
        assertThat(question.has("oneOf")).isFalse();
        assertThat(question.path("anyOf").path(0).path("type").stringValue()).isEqualTo("Note");
        assertThat(question.path("anyOf").path(1).path("name").stringValue()).isEqualTo("Coffee");
        assertThat(question.path("anyOf").path(1).path("replies").path("totalItems").asInt()).isZero();
        assertThat(question.path("endTime").isString()).isTrue();
        assertThat(question.has("closed")).isFalse();
        assertThat(question.path("votersCount").asInt()).isZero();
    }

    @Test
    void sendsTheCountsOfALocalPollToItsFollowersAndVoters() {
        var host = newHost();
        follow(host, "bob");
        var voterHost = newHost();
        var voter = remoteActor(voterHost, "carol");
        var status = poll(false);
        awaitActivity(host, status.uri() + "#create");
        assertThat(polls.receiveVote(status.id(), voter.id(), URI.create(voter.uri() + "#votes/1"), "Coffee"))
                .isEqualTo(PollService.VoteOutcome.COUNTED);
        await().atMost(WAIT).until(() -> resultsJobs(status) >= 1);

        deliveries.sendResults(new PollResultsJob(status.id(), author.id(), Instant.now()));

        for (var inboxHost : List.of(host, voterHost)) {
            var update = awaitActivity(inboxHost, status.uri() + "#updates/");
            assertThat(update.path("type").stringValue()).isEqualTo("Update");
            assertThat(update.path("object").path("oneOf").path(1).path("replies").path("totalItems").asInt())
                    .isEqualTo(1);
            assertThat(update.path("object").has("updated")).isFalse();
        }
    }

    @Test
    void sendsALocalVoteToThePollsAuthorOnly() {
        var host = newHost();
        var remote = remoteActor(host, "dave");
        var status = statuses.findStored(remotePoll(remote)).orElseThrow();

        polls.vote(status.id(), author.id(), List.of(1)).orElseThrow();

        var vote = awaitActivity(host, author.uri() + "#votes/");
        assertThat(vote.path("type").stringValue()).isEqualTo("Create");
        assertThat(vote.path("actor").stringValue()).isEqualTo(author.uri().toString());
        assertThat(strings(vote.path("to"))).containsExactly(remote.uri().toString());
        var note = vote.path("object");
        assertThat(note.path("type").stringValue()).isEqualTo("Note");
        assertThat(note.path("name").stringValue()).isEqualTo("No");
        assertThat(note.path("inReplyTo").stringValue()).isEqualTo(status.uri().toString());
        assertThat(note.path("attributedTo").stringValue()).isEqualTo(author.uri().toString());
        assertThat(note.has("content")).isFalse();
        assertThat(received.stream().filter(request -> request.body().contains("#votes/")))
                .allMatch(request -> request.host().equals(host) && request.path().equals("/users/dave/inbox"));
    }

    @Test
    void sendsTheFinalCountsWhenALocalPollEnds() {
        var host = newHost();
        follow(host, "bob");
        var status = poll(false);
        awaitActivity(host, status.uri() + "#create");
        jdbc.sql("UPDATE poll SET expires_at = now() - interval '1 second' WHERE status_id = :id")
                .param("id", status.id()).update();

        polls.endDue();

        var update = awaitActivity(host, status.uri() + "#updates/");
        assertThat(update.path("object").path("closed").isString()).isTrue();
    }

    private Status poll(boolean multiple) {
        return statuses.create(author.id(), new NewStatus("Which?", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, null, new NewPoll(List.of("Tea", "Coffee"), 600, multiple, false)));
    }

    private UUID remotePoll(Actor remote) {
        return statuses.storeRemote(new RemoteStatus(
                URI.create(remote.uri() + "/notes/" + UUID.randomUUID()), null, remote.id(), "<p>Which?</p>", null,
                null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(), List.of(), null, null,
                new RemotePoll(List.of("Yes", "No"), List.of(0, 0), false, Instant.now().plusSeconds(600), null, 0)))
                .orElseThrow();
    }

    private long resultsJobs(Status status) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = :task AND execution_time > now() AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("task", PollDeliveries.RESULTS_TASK.getTaskName())
                .param("id", "%" + status.id() + "%")
                .query(Long.class)
                .single();
    }

    private String newHost() {
        return "polls" + next.incrementAndGet() + ".test";
    }

    private void follow(String host, String username) {
        var follower = remoteActor(host, username);
        var now = Instant.now();
        transactions.executeWithoutResult(transaction -> follows.receive(
                follower.id(), follower.uri(), author.id(), follower.uri() + "#follow",
                new FollowService.Arrival(now, now)));
    }

    private Actor remoteActor(String host, String username) {
        var domain = host + ":" + server.getAddress().getPort();
        var uri = URI.create("http://" + domain + "/users/" + username);
        return actors.upsertRemote(
                new RemoteActorProfile(uri, username, domain, URI.create(uri + "/inbox"), null, null, null));
    }

    private JsonNode awaitActivity(String host, String activityId) {
        await().atMost(WAIT).until(() -> receivedBy(host).stream()
                .anyMatch(request -> request.body().contains(activityId)));
        return json.readTree(receivedBy(host).stream()
                .filter(candidate -> candidate.body().contains(activityId))
                .findFirst()
                .orElseThrow()
                .body());
    }

    private List<Received> receivedBy(String host) {
        return received.stream().filter(request -> request.host().equals(host)).toList();
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::stringValue).toList();
    }

    private record Received(String host, String path, String body) {
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
