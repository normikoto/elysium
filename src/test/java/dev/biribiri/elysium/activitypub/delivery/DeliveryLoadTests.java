package dev.biribiri.elysium.activitypub.delivery;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.ActorService;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Sends a large fan-out through the real scheduler with production settings, against a slow-ish server on the loopback
 * address, and prints what it cost. Run with {@code ./gradlew loadTest}, which needs Docker. {@code ./gradlew test} leaves it
 * out, because it takes minutes and its numbers are what matter.
 *
 * <p>Only what has to hold at any speed is asserted, along with finishing in time. The numbers depend on the machine, so
 * they're for comparing runs, and they're printed even if a scenario runs out of time. Suspending the machine during a run
 * makes its numbers useless, so the time it was suspended is printed too.
 */
@Tag("load")
@Import({TestcontainersConfiguration.class, DeliveryLoadTests.Config.class})
@SpringBootTest
class DeliveryLoadTests {

    private static final Duration LATENCY = Duration.ofMillis(200);
    private static final Duration WAIT = Duration.ofMinutes(10);

    @Autowired
    private ActivityDeliveries deliveries;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private DataSource dataSource;

    private final Map<String, AtomicInteger> receivedByInbox = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> runningByHost = new ConcurrentHashMap<>();
    private final AtomicInteger mostRunningForAHost = new AtomicInteger();
    private HttpServer server;
    private UUID actorId;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var host = exchange.getRequestHeaders().getFirst("Host").split(":")[0];
            var running = runningByHost.computeIfAbsent(host, ignored -> new AtomicInteger());
            mostRunningForAHost.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(LATENCY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            receivedByInbox.computeIfAbsent(host + exchange.getRequestURI().getPath(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        actors.createLocalIfAbsent("loadtester", actor -> {
        });
        actorId = actors.findLocalByUsername("loadtester").orElseThrow().id();
    }

    /**
     * Scenarios share a database, so one that ran out of time would otherwise leave its jobs and its hosts' backoff to the next.
     */
    @AfterEach
    void stop() {
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = 'activity-delivery'").update();
        jdbc.sql("DELETE FROM outbound_activity").update();
        jdbc.sql("DELETE FROM federation_host").update();
        server.stop(0);
    }

    @Test
    void manyInboxesOnOneHost() throws Exception {
        run("1000 inboxes on one host", inboxes(1, 1000));
    }

    @Test
    void aFanOutAcrossManyHosts() throws Exception {
        run("2000 inboxes across 500 hosts", inboxes(500, 4));
    }

    private void run(String name, List<URI> inboxes) throws Exception {
        var id = "https://elysium.example/activities/" + UUID.randomUUID();
        var activity = new OutboundActivity(id, actorId, "{\"id\":\"" + id + "\",\"text\":\"load\"}");
        var pool = dataSource.unwrap(HikariDataSource.class);
        var pending = new AtomicBoolean(true);
        var peakAwaiting = new AtomicInteger();
        var peakActive = new AtomicInteger();
        var samples = new AtomicInteger();
        var samplesAwaiting = new AtomicInteger();
        var sampler = Thread.ofVirtual().start(() -> {
            while (pending.get()) {
                var bean = pool.getHikariPoolMXBean();
                var awaiting = bean.getThreadsAwaitingConnection();
                peakAwaiting.accumulateAndGet(awaiting, Math::max);
                peakActive.accumulateAndGet(bean.getActiveConnections(), Math::max);
                samples.incrementAndGet();
                if (awaiting > 0) {
                    samplesAwaiting.incrementAndGet();
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        var started = Instant.now();
        var startedNanos = System.nanoTime();
        transactions.executeWithoutResult(status -> deliveries.enqueue(activity, inboxes));
        var queued = Instant.now();

        var drained = true;
        try {
            await().atMost(WAIT).pollInterval(Duration.ofSeconds(1)).until(() -> jobsLeft() == 0);
        } catch (ConditionTimeoutException e) {
            drained = false;
        } finally {
            pending.set(false);
        }
        var finished = Instant.now();
        // The system clock keeps going while the machine is suspended, and this one doesn't.
        var awake = Duration.ofNanos(System.nanoTime() - startedNanos);
        var suspended = Duration.between(started, finished).minus(awake);
        suspended = suspended.isNegative() ? Duration.ZERO : suspended;
        sampler.interrupt();

        var delivered = receivedByInbox.size();
        var running = Duration.between(queued, finished).minus(suspended);
        var runs = runsSince(started);
        var failed = failedRunsSince(started);
        System.out.printf("""

                        === %s ===
                        Queuing:               %d ms
                        Finished:              %s, after %d s running
                        Suspended:             %d s
                        Delivered:             %d of %d (%.0f a minute)
                        Runs per delivery:     %.2f (%d history rows)
                        Failed runs:           %d
                        Most at once per host: %d
                        Peak connections used: %d of %d
                        Peak threads waiting:  %d (waiting in %d%% of samples)
                        %n""",
                name,
                Duration.between(started, queued).toMillis(),
                drained ? "yes" : "NO, ran out of time",
                running.toSeconds(),
                suspended.toSeconds(),
                delivered,
                inboxes.size(),
                delivered * 60_000.0 / Math.max(1, running.toMillis()),
                (double) runs / Math.max(1, delivered),
                runs,
                failed,
                mostRunningForAHost.get(),
                peakActive.get(),
                pool.getMaximumPoolSize(),
                peakAwaiting.get(),
                samples.get() == 0 ? 0 : samplesAwaiting.get() * 100 / samples.get());

        assertThat(failed).isZero();
        assertThat(mostRunningForAHost.get()).isLessThanOrEqualTo(HostGate.MAX_REQUESTS_PER_HOST);
        assertThat(drained).as("Every job finished in time").isTrue();
        assertThat(receivedByInbox).hasSize(inboxes.size());
        assertThat(receivedByInbox.values()).allMatch(count -> count.get() == 1);
    }

    private List<URI> inboxes(int hosts, int perHost) {
        var port = server.getAddress().getPort();
        var inboxes = new ArrayList<URI>();
        for (var host = 0; host < hosts; host++) {
            for (var inbox = 0; inbox < perHost; inbox++) {
                inboxes.add(URI.create("http://load" + host + ".test:" + port + "/users/" + inbox + "/inbox"));
            }
        }
        return inboxes;
    }

    private long jobsLeft() {
        return jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'activity-delivery'")
                .query(Long.class)
                .single();
    }

    private long runsSince(Instant time) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_execution_logs
                        WHERE task_name = 'activity-delivery' AND time_started >= :time
                        """)
                .param("time", time.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private long failedRunsSince(Instant time) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_execution_logs
                        WHERE task_name = 'activity-delivery' AND time_started >= :time AND NOT succeeded
                        """)
                .param("time", time.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
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
