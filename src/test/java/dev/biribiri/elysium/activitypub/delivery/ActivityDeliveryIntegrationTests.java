package dev.biribiri.elysium.activitypub.delivery;

import dev.biribiri.elysium.moderation.DomainBlocks;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.serializer.Jackson3Serializer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real scheduler against a real server on the loopback address. Any hostname reaches that server, so each test
 * uses its own hosts, and they can't affect each other's backoff or limits.
 */
@Import({TestcontainersConfiguration.class, ActivityDeliveryIntegrationTests.Config.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class ActivityDeliveryIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final AtomicInteger nextHost = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private ActivityDeliveries deliveries;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Scheduler scheduler;
    @Autowired
    private ActorService actors;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private HostGate gate;
    @Autowired
    private FederationHttpClient http;
    @Autowired
    private DomainBlocks blocks;

    private final Queue<Received> received = new ConcurrentLinkedQueue<>();
    private final Map<String, Queue<Answer>> answers = new ConcurrentHashMap<>();
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private HttpServer server;
    private UUID actorId;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::answer);
        server.start();
        actors.createLocalIfAbsent("deliverer", actor -> {
        });
        actorId = actors.findLocalByUsername("deliverer").orElseThrow().id();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // Transactions

    @Test
    void aRollbackLeavesNeitherTheJobNorTheActivity() {
        var activity = activity();

        transactions.executeWithoutResult(status -> {
            deliveries.enqueue(activity, List.of(inboxOn(newHost())));
            status.setRollbackOnly();
        });

        assertThat(jobs(activity)).isZero();
        assertThat(activities(activity)).isZero();
    }

    @Test
    void queuingOutsideATransactionFails() {
        assertThatThrownBy(() -> deliveries.enqueue(activity(), List.of(inboxOn(newHost()))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void queuesFromAListenerToAnEventPublishedInATransaction() {
        var activity = activity();
        var host = newHost();

        transactions.executeWithoutResult(status -> events.publishEvent(new SampleEvent(activity, inboxOn(host))));

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1);
        await().atMost(WAIT).until(() -> publications(activity) == 0);
    }

    // Suspended and deleted accounts

    @Test
    void anActivityThatCanBeDroppedIsFinishedWithoutSendingWhileItsActorIsSuspended() {
        var signer = newSigner();
        var activity = activityBy(signer, WhileSuspended.DROP);
        var host = newHost();
        actors.suspend(signer);

        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void anActivityThatWaitsIsSentOnceTheSuspensionEnds() {
        var signer = newSigner();
        var activity = activityBy(signer, WhileSuspended.WAIT);
        var host = newHost();
        actors.suspend(signer);

        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> executionTime(activity).isAfter(Instant.now().plus(Duration.ofMinutes(1))));
        assertThat(receivedBy(host)).isEmpty();
        assertThat(jobs(activity)).isOne();
        assertThat(failures(activity)).isZero();

        actors.unsuspend(signer);
        runNow(activity);

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1 && jobs(activity) == 0);
    }

    @Test
    void anActivityThatWaitsGivesUpQuietlyOnceItsActivityCouldBeCleanedUp() {
        var signer = newSigner();
        var activity = activityBy(signer, WhileSuspended.WAIT);
        var host = newHost();
        actors.suspend(signer);
        enqueue(activity, inboxOn(host));
        await().atMost(WAIT).until(() -> executionTime(activity).isAfter(Instant.now().plus(Duration.ofMinutes(1))));

        ageJob(activity, Duration.ofDays(40));
        runNow(activity);

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void anActivityThatWaitsIsNotSentOnceItsActorIsDeleted() {
        var signer = newSigner();
        var activity = activityBy(signer, WhileSuspended.WAIT);
        var host = newHost();
        actors.markDeleted(signer);

        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void anActivityAboutTheAccountIsSentWhileItsActorIsSuspendedOrDeleted() {
        var signer = newSigner();
        actors.suspend(signer);
        var host = newHost();
        var suspended = activityBy(signer, WhileSuspended.SEND);

        enqueue(suspended, inboxOn(host));

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1 && jobs(suspended) == 0);

        actors.markDeleted(signer);
        // The listener for this cancels the actor's activities, other than its Delete, so this has to be queued after it's run.
        await().atMost(WAIT).until(() -> publicationsAbout(signer) == 0);
        var deleted = activityBy(signer, WhileSuspended.SEND);

        enqueue(deleted, inboxOn(host));

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 2 && jobs(deleted) == 0);
        assertThat(receivedBy(host).getLast().header("Signature")).contains("#main-key");
    }

    @Test
    void nothingIsSentToASuspendedActorsInbox() {
        var host = newHost();
        var inbox = inboxOn(host);
        var other = newHost();
        var actor = actors.upsertRemote(new RemoteActorProfile(
                URI.create("https://" + host + "/users/bob"), "bob", host, inbox, null, null, null));
        var queuedBefore = activity();
        enqueue(queuedBefore, inbox);
        actors.suspend(actor.id());
        var queuedAfter = activity();

        enqueue(queuedAfter, inbox, inboxOn(other));

        await().atMost(WAIT).until(() -> receivedBy(other).size() == 1 && jobs(queuedAfter) == 0);
        await().atMost(WAIT).until(() -> jobs(queuedBefore) == 0);
        // The first can have started before the suspension. The second can't.
        assertThat(receivedBy(host).stream().filter(request -> request.body().contains(queuedAfter.id()))).isEmpty();
    }

    @Test
    void anInboxThatAnActiveActorAlsoUsesStillGetsActivities() {
        var host = newHost();
        var inbox = inboxOn(host);
        var suspended = actors.upsertRemote(new RemoteActorProfile(
                URI.create("https://" + host + "/users/bob"), "bob", host, inbox, null, null, null));
        actors.upsertRemote(new RemoteActorProfile(
                URI.create("https://" + host + "/users/carol"), "carol", host, inbox, null, null, null));
        actors.suspend(suspended.id());
        var activity = activity();

        enqueue(activity, inbox);

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1);
    }

    private static OutboundActivity activityBy(UUID signer, WhileSuspended whileSuspended) {
        return new OutboundActivity("https://elysium.example/activities/" + UUID.randomUUID(), signer, "{}", whileSuspended);
    }

    private UUID newSigner() {
        var name = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        actors.createLocalIfAbsent(name, actor -> {
        });
        return actors.findLocalByUsername(name).orElseThrow().id();
    }

    // Sending

    @Test
    void sendsTheSignedActivityAndThenRemovesTheJob() throws Exception {
        var activity = activity();
        var host = newHost();

        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1 && jobs(activity) == 0);
        var request = receivedBy(host).getFirst();
        var sent = (ObjectNode) JSON.readTree(request.body());
        assertThat(sent.get("proof").get("verificationMethod").stringValue()).endsWith("/users/deliverer#ed25519-key");
        sent.remove("proof");
        sent.remove("@context");
        assertThat(sent).isEqualTo(JSON.readTree(activity.body()));
        assertThat(request.header("Content-Type")).isEqualTo("application/activity+json");
        assertThat(request.header("Signature")).contains("keyId=\"", "/users/deliverer#main-key\"");
        var digest = MessageDigest.getInstance("SHA-256").digest(request.body().getBytes(StandardCharsets.UTF_8));
        assertThat(request.header("Digest")).isEqualTo("SHA-256=" + Base64.getEncoder().encodeToString(digest));
    }

    @Test
    void aReplacedBodyGetsAProofOfItsOwn() {
        var activity = activity();
        var replacement = "{\"id\":\"" + activity.id() + "\",\"text\":\"edited\"}";

        var bodies = transactions.execute(status -> {
            deliveries.enqueue(activity, List.of(inboxOn(newHost())));
            var before = storedBody(activity);
            deliveries.replaceBody(activity.id(), replacement);
            // So that nothing is sent.
            status.setRollbackOnly();
            return List.of(before, storedBody(activity));
        });

        var before = (ObjectNode) JSON.readTree(bodies.get(0));
        var after = (ObjectNode) JSON.readTree(bodies.get(1));
        assertThat(after.get("text").stringValue()).isEqualTo("edited");
        assertThat(after.get("proof").get("verificationMethod")).isEqualTo(before.get("proof").get("verificationMethod"));
        assertThat(after.get("proof").get("proofValue")).isNotEqualTo(before.get("proof").get("proofValue"));
    }

    private String storedBody(OutboundActivity activity) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE activity_id = :id")
                .param("id", activity.id())
                .query(String.class)
                .single();
    }

    @Test
    void anActivityWithNoActorIsSignedAsTheInstanceActor() {
        var id = "https://elysium.example/reports/" + UUID.randomUUID();
        var activity = OutboundActivity.asInstance(id, "{\"id\":\"" + id + "\",\"type\":\"Flag\"}");
        var host = newHost();

        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1 && jobs(activity) == 0);
        assertThat(receivedBy(host).getFirst().header("Signature")).contains("/actor#main-key\"");
        assertThat(failures(activity)).isZero();
    }

    @Test
    void eachInboxGetsOneJob() {
        var activity = activity();
        var host = newHost();
        var other = newHost();

        enqueue(activity, inboxOn(host), inboxOn(host), inboxOn(other));
        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1 && receivedBy(other).size() == 1);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(host).size() == 1);
    }

    @Test
    void doesNotSendACancelledActivity() {
        var activity = activity();
        var host = newHost();
        transactions.executeWithoutResult(status -> {
            deliveries.enqueue(activity, List.of(inboxOn(host)));
            deliveries.cancel(activity.id());
        });

        await().atMost(WAIT).until(() -> jobs(activity) == 0);

        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void storesNothingWhileEveryInboxIsOnABlockedServerAndSendsAgainOnceTheBlockIsRemoved() {
        var blocked = activity();
        var host = newHost();
        var block = blocks.create(host, null);

        enqueue(blocked, inboxOn("sub." + host), inboxOn(host));

        assertThat(jobs(blocked)).isZero();
        assertThat(activities(blocked)).isZero();

        blocks.delete(block.id());
        enqueue(activity(), inboxOn(host));

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1);
    }

    @Test
    void leavesOutABlockedServerButStillSendsToOthers() {
        var activity = activity();
        var blocked = newHost();
        var allowed = newHost();
        blocks.create(blocked, null);

        transactions.executeWithoutResult(status ->
                deliveries.enqueue(activity, List.of(inboxOn(blocked), inboxOn(allowed)), "attempt"));

        await().atMost(WAIT).until(() -> receivedBy(allowed).size() == 1);
        assertThat(receivedBy(blocked)).isEmpty();
    }

    @Test
    void finishesAJobQueuedBeforeABlockWithoutSendingOrFailing() {
        var activity = activity();
        var host = newHost();
        answer(host, new Answer(503, null));
        enqueue(activity, inboxOn(host));
        await().atMost(WAIT).until(() -> failures(activity) == 1);
        var attempts = receivedBy(host).size();

        blocks.create(host, null);
        runNow(activity);

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).hasSize(attempts);
    }

    @Test
    void doesNotHoldADatabaseConnectionWhileSending() throws Exception {
        var activity = activity();
        var host = newHost();
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handlers.put(host, exchange -> {
            arrived.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new Answer(200, null);
        });
        enqueue(activity, inboxOn(host));
        assertThat(arrived.await(30, TimeUnit.SECONDS)).isTrue();

        try {
            var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            await().atMost(WAIT).until(() -> pool.getActiveConnections() == 0);
        } finally {
            release.countDown();
        }
    }

    // Retries

    @Test
    void retriesATemporaryFailureWithTheSameBodySignedAgainAndEndsTheServersBackoff() throws Exception {
        var activity = activity();
        var host = newHost();
        answer(host, new Answer(500, null));

        enqueue(activity, inboxOn(host));
        await().atMost(WAIT).until(() -> failures(activity) == 1);

        var delay = Duration.between(Instant.now(), executionTime(activity));
        assertThat(delay).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(80));
        assertThat(failureMessages(activity)).contains("The inbox answered with a 500");
        assertThat(hostRows(host)).isOne();

        // Dates in signatures have a resolution of one second.
        Thread.sleep(1100);
        runNow(activity);

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        var requests = receivedBy(host);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
        assertThat(requests.get(1).header("Date")).isNotEqualTo(requests.get(0).header("Date"));
        assertThat(requests.get(1).header("Signature")).isNotEqualTo(requests.get(0).header("Signature"));
        assertThat(hostRows(host)).isZero();
    }

    @Test
    void parksAPermanentFailureWithItsReasonAndDropsItIfNobodyTouchesIt() {
        var activity = activity();
        var host = newHost();
        answer(host, new Answer(410, null));

        enqueue(activity, inboxOn(host));
        await().atMost(WAIT).until(() -> failures(activity) == 1);

        assertThat(executionTime(activity)).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(Duration.ofMinutes(1)));
        assertThat(failureMessages(activity)).contains("The inbox answered with a 410");
        assertThat(receivedBy(host)).hasSize(1);

        jdbc.sql("""
                        UPDATE scheduled_tasks SET last_failure = now() - INTERVAL '31 days', execution_time = now()
                        WHERE task_instance = :instance
                        """)
                .param("instance", instanceOf(activity))
                .update();

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).hasSize(1);
    }

    @Test
    void parksAJobThatHasBeenFailingForTooLong() {
        var activity = activity();
        var host = newHost();
        answer(host, new Answer(500, null));
        transactions.executeWithoutResult(status -> {
            deliveries.enqueue(activity, List.of(inboxOn(host)));
            ageJob(activity, Duration.ofDays(3));
        });

        await().atMost(WAIT).until(() -> failures(activity) == 1);

        assertThat(executionTime(activity)).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(Duration.ofMinutes(1)));
    }

    @Test
    void refusesInboxesThatTheClientWillNotReach() {
        var activity = activity();

        enqueue(activity, URI.create("ftp://" + newHost() + "/inbox"));
        await().atMost(WAIT).until(() -> failures(activity) == 1);

        assertThat(failureMessages(activity)).contains("The inbox URL isn't allowed");
        assertThat(executionTime(activity)).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    // Parked jobs

    @Test
    void anAdminCanRetryAParkedJobTheWayTheUiDoesAndItIsParkedAgainIfItFailsAgain() {
        var activity = activity();
        var host = newHost();
        answer(host, new Answer(410, null));
        answer(host, new Answer(410, null));
        enqueue(activity, inboxOn(host));
        await().atMost(WAIT).until(() -> failures(activity) == 1);
        var firstTime = executionTime(activity);

        // db-scheduler-ui's rerun button calls this.
        scheduler.reschedule(ActivityDeliveries.TASK.instanceId(instanceOf(activity)), Instant.now());

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 2 && executionTime(activity).isAfter(firstTime));
        assertThat(executionTime(activity)).isAfter(Instant.now().plus(Duration.ofDays(29)));

        scheduler.reschedule(ActivityDeliveries.TASK.instanceId(instanceOf(activity)), Instant.now());

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).hasSize(3);
    }

    @Test
    void retryingAJobOldEnoughToHaveLostItsActivityFailsWithTheReason() {
        var activity = activity();
        var host = newHost();
        answer(host, new Answer(410, null));
        enqueue(activity, inboxOn(host));
        await().atMost(WAIT).until(() -> failures(activity) == 1);
        var instance = instanceOf(activity);
        ageJob(activity, Duration.ofDays(34));
        deliveries.cancel(activity.id());

        scheduler.reschedule(ActivityDeliveries.TASK.instanceId(instance), Instant.now());

        // By its history and not its count of failures, which rescheduling resets.
        await().atMost(WAIT).until(() -> failureMessages(activity).stream()
                .anyMatch(message -> message != null && message.contains("Its activity is gone")));
        await().atMost(WAIT).until(() -> failures(activity) == 1);
        assertThat(executionTime(activity)).isAfter(Instant.now().plus(Duration.ofDays(29)));
        assertThat(receivedBy(host)).hasSize(1);
    }

    @Test
    void queuingAnActivityAgainKeepsItForAsLongAsTheNewJobCanLast() {
        var activity = activity();
        enqueue(activity, inboxOn(newHost()));
        jdbc.sql("UPDATE outbound_activity SET created_at = now() - INTERVAL '33 days' WHERE activity_id = :id")
                .param("id", activity.id())
                .update();

        enqueue(activity, inboxOn(newHost()));

        var created = jdbc.sql("SELECT created_at FROM outbound_activity WHERE activity_id = :id")
                .param("id", activity.id())
                .query(OffsetDateTime.class)
                .single();
        assertThat(created.toInstant()).isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));
    }

    // One server's problems stay its own

    @Test
    void aCancelledJobFinishesRatherThanGivingUpOnAFailingServer() {
        var host = newHost();
        jdbc.sql("INSERT INTO federation_host (host, consecutive_failures, retry_after) VALUES (:host, 9, now() + INTERVAL '1 hour')")
                .param("host", host)
                .update();
        var activity = activity();

        transactions.executeWithoutResult(status -> {
            deliveries.enqueue(activity, List.of(inboxOn(host)));
            ageJob(activity, Duration.ofDays(3));
            deliveries.cancel(activity.id());
        });

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(jdbc.sql("SELECT count(*) FROM scheduled_execution_logs WHERE NOT succeeded AND task_data IS NOT NULL AND convert_from(task_data, 'UTF8') LIKE :id")
                .param("id", "%" + activity.id() + "%")
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void parksAJobThatHasWaitedTooLongForABusyServerAndAnAdminCanRetryIt() {
        var host = newHost();
        var activity = activity();
        whileBusy(host, () -> {
            transactions.executeWithoutResult(status -> {
                deliveries.enqueue(activity, List.of(inboxOn(host)));
                ageJob(activity, Duration.ofDays(3), Duration.ofDays(3));
            });
            await().atMost(WAIT).until(() -> failures(activity) == 1);
            assertThat(failureMessages(activity)).anyMatch(message -> message.contains("has been too busy for too long"));
            assertThat(receivedBy(host)).isEmpty();

            scheduler.reschedule(ActivityDeliveries.TASK.instanceId(instanceOf(activity)), Instant.now());
            waitForTurns(activity, 3);
            assertThat(failures(activity)).as("failures while waiting again after the retry").isZero();
        });

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).hasSize(1);
    }

    @Test
    void anOldJobThatHasOnlyJustFoundItsServerBusyWaitsForItsTurn() {
        var host = newHost();
        var activity = activity();
        whileBusy(host, () -> {
            transactions.executeWithoutResult(status -> {
                deliveries.enqueue(activity, List.of(inboxOn(host)));
                ageJob(activity, Duration.ofDays(3));
            });
            waitForTurns(activity, 3);
        });

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).hasSize(1);
        assertThat(history(activity, false)).isZero();
    }

    @Test
    void aCancelledJobFinishesRatherThanGivingUpOnABusyServer() {
        var host = newHost();
        var activity = activity();
        whileBusy(host, () -> {
            transactions.executeWithoutResult(status -> {
                deliveries.enqueue(activity, List.of(inboxOn(host)));
                ageJob(activity, Duration.ofDays(3), Duration.ofDays(3));
                deliveries.cancel(activity.id());
            });

            await().atMost(WAIT).until(() -> jobs(activity) == 0);
        });

        // History is written in the background, so this waits for the finished run's row.
        await().atMost(WAIT).until(() -> history(activity, true) == 1);
        assertThat(history(activity, false)).isZero();
    }

    @Test
    void anActivityThatWaitsForASuspensionKeepsWaitingWhileItsServerIsBusy() {
        var signer = newSigner();
        var activity = activityBy(signer, WhileSuspended.WAIT);
        var host = newHost();
        actors.suspend(signer);
        whileBusy(host, () -> {
            transactions.executeWithoutResult(status -> {
                deliveries.enqueue(activity, List.of(inboxOn(host)));
                ageJob(activity, Duration.ofDays(3), Duration.ofDays(3));
            });

            await().atMost(WAIT).until(() -> executionTime(activity).isAfter(Instant.now().plus(Duration.ofMinutes(1))));
        });

        assertThat(jobs(activity)).isOne();
        assertThat(failures(activity)).isZero();
    }

    @Test
    void runsThatOnlyWaitAreNotKeptInHistory() {
        var host = newHost();
        var activity = activity();
        whileBusy(host, () -> {
            enqueue(activity, inboxOn(host));
            waitForTurns(activity, 3);
        });

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(receivedBy(host)).hasSize(1);
        // History is written in the background, so the count has to stay put for a while to show that nothing else is coming.
        await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> history(activity, true) == 1);
        assertThat(history(activity, false)).isZero();
    }

    @Test
    void jobsWaitingForAServerGetTheirOwnTurns() {
        var host = newHost();
        var now = Instant.now().plusSeconds(60);

        var first = gate.nextTurn(host, now);
        var second = gate.nextTurn(host, now);

        assertThat(first).isAfter(now);
        assertThat(second).isAfter(first);
        assertThat(gate.nextTurn(newHost(), now)).isEqualTo(first);
    }

    @Test
    void aServerThatIsSlowToAnswerDoesNotHoldUpOthers() throws Exception {
        var slow = newHost();
        var working = newHost();
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handlers.put(slow, exchange -> {
            arrived.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new Answer(500, null);
        });
        try {
            enqueue(activity(), inboxOn(slow));
            assertThat(arrived.await(30, TimeUnit.SECONDS)).isTrue();

            // Queued only once the slow request is held, so that it can't have been sent first by chance.
            enqueue(activity(), inboxOn(working));
            await().atMost(WAIT).until(() -> receivedBy(working).size() == 1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void limitsRequestsRunningAtOnceForOneServerAndStillDeliversEverything() {
        var host = newHost();
        var running = new AtomicInteger();
        var mostRunning = new AtomicInteger();
        handlers.put(host, exchange -> {
            mostRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(300);
            running.decrementAndGet();
            return new Answer(200, null);
        });

        var activity = activity();
        var inboxes = IntStream.range(0, 8).mapToObj(i -> URI.create(inboxOn(host) + "/" + i)).toArray(URI[]::new);
        enqueue(activity, inboxes);

        await().atMost(Duration.ofSeconds(60)).until(() -> receivedBy(host).size() == 8);
        assertThat(mostRunning.get()).isLessThanOrEqualTo(HostGate.MAX_REQUESTS_PER_HOST);
    }

    @Test
    void backsOffFromAServerThatKeepsFailingWithoutUsingUpOtherJobsRetries() {
        var host = newHost();
        var first = activity();
        for (var i = 0; i < HostGate.FAILURES_BEFORE_BACKOFF; i++) {
            answer(host, new Answer(500, null));
        }
        enqueue(first, inboxOn(host));
        for (var i = 1; i <= HostGate.FAILURES_BEFORE_BACKOFF; i++) {
            var expected = i;
            await().atMost(WAIT).until(() -> failures(first) == expected);
            if (i < HostGate.FAILURES_BEFORE_BACKOFF) {
                runNow(first);
            }
        }
        assertThat(retryAfter(host)).isAfter(Instant.now());

        var second = activity();
        enqueue(second, inboxOn(host));

        await().atMost(WAIT).until(() -> {
            var time = jobs(second) == 1 ? executionTime(second) : Instant.EPOCH;
            return time.isAfter(Instant.now().plusSeconds(10));
        });
        assertThat(failures(second)).isZero();
        assertThat(receivedBy(host)).hasSize(HostGate.FAILURES_BEFORE_BACKOFF);
    }

    static Stream<Arguments> retryAfterAnswers() {
        var date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(2));
        return Stream.of(
                Arguments.of(429, "3600", Duration.ofHours(1)),
                Arguments.of(503, date, Duration.ofHours(2)),
                // Asked for years, and held to the most a server can ask for.
                Arguments.of(429, "99999999", Duration.ofHours(6)));
    }

    @ParameterizedTest
    @MethodSource("retryAfterAnswers")
    void honoursRetryAfterInSecondsOrAsADateUpToALimit(int status, String retryAfter, Duration expected) {
        var host = newHost();
        answer(host, new Answer(status, retryAfter));
        var activity = activity();

        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> failures(activity) == 1);
        assertThat(retryAfter(host)).isCloseTo(Instant.now().plus(expected), within(Duration.ofMinutes(1)));
    }

    @Test
    void parksAJobWhenItsServerIsStillBackingOffAtTheLimit() {
        var host = newHost();
        jdbc.sql("INSERT INTO federation_host (host, consecutive_failures, retry_after) VALUES (:host, 9, now() + INTERVAL '1 hour')")
                .param("host", host)
                .update();
        var activity = activity();
        transactions.executeWithoutResult(status -> {
            deliveries.enqueue(activity, List.of(inboxOn(host)));
            ageJob(activity, Duration.ofDays(3));
        });

        await().atMost(WAIT).until(() -> failures(activity) == 1);

        assertThat(failureMessages(activity)).anyMatch(message -> message.contains("has been failing for too long"));
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void aFailureDoesNotShortenABackoffThatAnotherRequestWasAskedFor() {
        var host = newHost();
        var now = Instant.now();

        gate.failed(host, now, Duration.ofHours(1));
        gate.failed(host, now, Duration.ZERO);

        assertThat(retryAfter(host)).isCloseTo(now.plus(Duration.ofHours(1)), within(Duration.ofMinutes(1)));
    }

    @Test
    void parksAJobWhoseInboxAnswersWithTooMuchAndCountsTheHostAsWorking() {
        var host = newHost();
        jdbc.sql("INSERT INTO federation_host (host, consecutive_failures, retry_after) VALUES (:host, 1, now())")
                .param("host", host)
                .update();
        handlers.put(host, exchange -> {
            exchange.sendResponseHeaders(200, 2 * 1024 * 1024);
            // Closing the exchange wouldn't send what's buffered, and the client only needs the length.
            exchange.getResponseBody().write(0);
            exchange.getResponseBody().flush();
            return null;
        });
        var activity = activity();

        enqueue(activity, inboxOn(host));

        await().atMost(WAIT).until(() -> failures(activity) == 1);
        assertThat(failureMessages(activity)).anyMatch(message -> message.contains("too large"));
        assertThat(executionTime(activity)).isAfter(Instant.now().plus(Duration.ofDays(29)));
        assertThat(receivedBy(host)).hasSize(1);
        assertThat(hostRows(host)).isZero();
    }

    @Test
    void runningOutOfLocalConnectionsDoesNotCountAgainstTheHost() throws Exception {
        var host = newHost();
        var connections = FederationHttpClient.MAX_CONNECTIONS_PER_HOST;
        var holding = new CountDownLatch(connections);
        var release = new CountDownLatch(1);
        handlers.put(host, exchange -> {
            holding.countDown();
            release.await();
            return new Answer(200, null);
        });
        try (var holders = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (var i = 0; i < connections; i++) {
                    holders.submit(() -> http.get(inboxOn(host), "application/json", new HttpHeaders()));
                }
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
                var activity = activity();

                enqueue(activity, inboxOn(host));

                await().atMost(WAIT).until(() -> failures(activity) == 1);
                assertThat(hostRows(host)).isZero();
                assertThat(receivedBy(host)).hasSize(connections);
            } finally {
                release.countDown();
            }
        }
    }

    // More than one worker

    @Test
    void aJobAbandonedByADeadWorkerIsPickedUpAgain() {
        var activity = activity();
        var host = newHost();
        transactions.executeWithoutResult(status -> {
            deliveries.enqueue(activity, List.of(inboxOn(host)));
            jdbc.sql("""
                            UPDATE scheduled_tasks
                            SET picked = true, picked_by = 'dead-worker', last_heartbeat = now() - INTERVAL '1 hour', version = version + 1
                            WHERE task_instance = :instance
                            """)
                    .param("instance", instanceOf(activity))
                    .update();
        });

        await().atMost(WAIT).until(() -> receivedBy(host).size() == 1 && jobs(activity) == 0);
    }

    // Helpers

    private OutboundActivity activity() {
        var id = "https://elysium.example/activities/" + UUID.randomUUID();
        return new OutboundActivity(id, actorId, "{\"id\":\"" + id + "\",\"text\":\"ünïcödé\"}");
    }

    private String newHost() {
        return "host" + nextHost.incrementAndGet() + ".test";
    }

    private URI inboxOn(String host) {
        return URI.create("http://" + host + ":" + server.getAddress().getPort() + "/inbox");
    }

    private void enqueue(OutboundActivity activity, URI... inboxes) {
        transactions.executeWithoutResult(status -> deliveries.enqueue(activity, List.of(inboxes)));
    }

    private void answer(String host, Answer answer) {
        answers.computeIfAbsent(host, ignored -> new ConcurrentLinkedQueue<>()).add(answer);
    }

    private void answer(HttpExchange exchange) throws IOException {
        var host = exchange.getRequestHeaders().getFirst("Host").split(":")[0];
        var headers = new java.util.HashMap<String, String>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(host, headers, body));

        var answer = new Answer(202, null);
        var handler = handlers.get(host);
        if (handler != null) {
            try {
                answer = handler.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            var queue = answers.get(host);
            var next = queue == null ? null : queue.poll();
            answer = next == null ? answer : next;
        }
        if (answer == null) {
            exchange.close();
            return;
        }
        if (answer.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", answer.retryAfter());
        }
        exchange.sendResponseHeaders(answer.status(), -1);
        exchange.close();
    }

    private List<Received> receivedBy(String host) {
        return received.stream().filter(request -> request.host().equals(host)).toList();
    }

    /**
     * Only for jobs with one inbox.
     */
    private String instanceOf(OutboundActivity activity) {
        return jdbc.sql("""
                        SELECT task_instance FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activity.id() + "%")
                .query(String.class)
                .single();
    }

    private long jobs(OutboundActivity activity) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activity.id() + "%")
                .query(Long.class)
                .single();
    }

    private long activities(OutboundActivity activity) {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id")
                .param("id", activity.id())
                .query(Long.class)
                .single();
    }

    private int failures(OutboundActivity activity) {
        return jdbc.sql("""
                        SELECT consecutive_failures FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery' AND picked = false
                          AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activity.id() + "%")
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    private Instant executionTime(OutboundActivity activity) {
        return jdbc.sql("SELECT execution_time FROM scheduled_tasks WHERE task_instance = :instance")
                .param("instance", instanceOf(activity))
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private List<String> failureMessages(OutboundActivity activity) {
        return jdbc.sql("SELECT exception_message FROM scheduled_execution_logs WHERE task_instance = :instance")
                .param("instance", instanceOf(activity))
                .query(String.class)
                .list();
    }

    private long hostRows(String host) {
        return jdbc.sql("SELECT count(*) FROM federation_host WHERE host = :host")
                .param("host", host)
                .query(Long.class)
                .single();
    }

    private Instant retryAfter(String host) {
        return jdbc.sql("SELECT retry_after FROM federation_host WHERE host = :host")
                .param("host", host)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /**
     * Takes every slot for the host while this runs, as deliveries that are slow to answer would.
     */
    private void whileBusy(String host, Runnable action) {
        for (var slot = 0; slot < HostGate.MAX_REQUESTS_PER_HOST; slot++) {
            assertThat(gate.tryAcquire(host)).isTrue();
        }
        try {
            action.run();
        } finally {
            for (var slot = 0; slot < HostGate.MAX_REQUESTS_PER_HOST; slot++) {
                gate.release(host);
            }
        }
    }

    /**
     * Counts the job's runs in its history, which outlast the job.
     */
    private void waitForTurns(OutboundActivity activity, int turns) {
        for (var turn = 0; turn < turns; turn++) {
            var before = executionTime(activity);
            await().atMost(WAIT).until(() -> executionTime(activity).isAfter(before));
        }
    }

    private long history(OutboundActivity activity, boolean succeeded) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_execution_logs
                        WHERE task_name = 'activity-delivery' AND succeeded = :succeeded
                          AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("succeeded", succeeded)
                .param("id", "%" + activity.id() + "%")
                .query(Long.class)
                .single();
    }

    private void runNow(OutboundActivity activity) {
        jdbc.sql("UPDATE scheduled_tasks SET execution_time = now() WHERE task_instance = :instance")
                .param("instance", instanceOf(activity))
                .update();
    }

    /**
     * Rewrites the job's data, since a job's time of queuing can't be set from outside.
     */
    private void ageJob(OutboundActivity activity, Duration age) {
        ageJob(activity, age, null);
    }

    /**
     * @param busyFor how long the job has been waiting for a turn with its host, or null if it isn't
     */
    private void ageJob(OutboundActivity activity, Duration age, Duration busyFor) {
        var queuedAt = Instant.now().minus(age).minusSeconds(60);
        var busySince = busyFor == null ? null : Instant.now().minus(busyFor).minusSeconds(60);
        var inbox = jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8')::json ->> 'inbox' FROM scheduled_tasks
                        WHERE task_instance = :instance
                        """)
                .param("instance", instanceOf(activity))
                .query(String.class)
                .single();
        var data = new Jackson3Serializer().serialize(new DeliveryJob(activity.id(), inbox, queuedAt, null, null, busySince));
        jdbc.sql("UPDATE scheduled_tasks SET task_data = :data WHERE task_instance = :instance")
                .param("data", data)
                .param("instance", instanceOf(activity))
                .update();
    }

    private long publications(OutboundActivity activity) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + activity.id() + "%")
                .query(Long.class)
                .single();
    }

    private long publicationsAbout(UUID actorId) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + actorId + "%")
                .query(Long.class)
                .single();
    }

    private record Received(String host, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private record Answer(int status, String retryAfter) {
    }

    @FunctionalInterface
    private interface Handler {
        /**
         * @return the answer to send, or null if this has already sent one
         */
        Answer handle(HttpExchange exchange) throws InterruptedException, IOException;
    }

    public record SampleEvent(OutboundActivity activity, URI inbox) {
    }

    /**
     * The same route that {@code StatusDeliveries} uses, for an activity that this test controls.
     */
    public static class SampleListener {
        private final ActivityDeliveries deliveries;

        SampleListener(ActivityDeliveries deliveries) {
            this.deliveries = deliveries;
        }

        @ApplicationModuleListener
        public void on(SampleEvent event) {
            deliveries.enqueue(event.activity(), List.of(event.inbox()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        FederationHttpClient loopbackClient() {
            return LoopbackClients.create();
        }

        @Bean
        SampleListener sampleListener(ActivityDeliveries deliveries) {
            return new SampleListener(deliveries);
        }
    }
}
