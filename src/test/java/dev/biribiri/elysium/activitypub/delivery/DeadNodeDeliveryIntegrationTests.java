package dev.biribiri.elysium.activitypub.delivery;

import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.ActorService;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import static org.awaitility.Awaitility.await;

/**
 * Keeps db-scheduler's default heartbeat, so that it takes half an hour to notice a dead node, and a test can tell whether a
 * delivery waited for that.
 */
@Import({TestcontainersConfiguration.class, DeadNodeDeliveryIntegrationTests.Config.class})
@SpringBootTest(properties = "db-scheduler.polling-interval=PT0.1S")
class DeadNodeDeliveryIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private ActivityDeliveries deliveries;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;

    private final Queue<String> received = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private UUID actorId;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        actorId = actors.createLocalIfAbsent("dead_node_deliverer", actor -> {
        }).orElseGet(() -> actors.findLocalByUsername("dead_node_deliverer").orElseThrow()).id();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void anActivityDoesNotStayBehindOneLeftPickedByANodeThatDied() {
        var inbox = inbox();
        var ahead = activity();
        pickedByADeadNode(ahead, inbox, "now() - INTERVAL '5 minutes'");
        var behind = activity();

        transactions.executeWithoutResult(status -> {
            deliveries.cancel(ahead.id());
            deliveries.enqueueAfter(behind, List.of(inbox), ahead.id());
        });

        await().atMost(WAIT).until(() -> received.stream().anyMatch(body -> body.contains(behind.id())));
    }

    @Test
    void anActivityStaysBehindOneThatIsStillRunning() {
        var inbox = inbox();
        var ahead = activity();
        pickedByADeadNode(ahead, inbox, "now()");
        var behind = activity();

        transactions.executeWithoutResult(status -> {
            deliveries.cancel(ahead.id());
            deliveries.enqueueAfter(behind, List.of(inbox), ahead.id());
        });

        await().during(Duration.ofSeconds(3)).atMost(WAIT)
                .until(() -> received.stream().noneMatch(body -> body.contains(behind.id())));
    }

    /**
     * The scheduler only rescues a job once its heartbeat is half an hour old, so here only the delivery's own check can let
     * the one behind it through.
     */
    private void pickedByADeadNode(OutboundActivity activity, URI inbox, String lastHeartbeat) {
        transactions.executeWithoutResult(status -> {
            deliveries.enqueue(activity, List.of(inbox));
            jdbc.sql("""
                            UPDATE scheduled_tasks
                            SET picked = true, picked_by = 'dead-node', last_heartbeat = %s, version = version + 1
                            WHERE task_name = 'activity-delivery' AND convert_from(task_data, 'UTF8') LIKE :id
                            """.formatted(lastHeartbeat))
                    .param("id", "%" + activity.id() + "%")
                    .update();
        });
    }

    private OutboundActivity activity() {
        var id = "https://elysium.example/activities/" + UUID.randomUUID();
        return new OutboundActivity(id, actorId, "{\"id\":\"" + id + "\"}");
    }

    private URI inbox() {
        return URI.create("http://dead-node-" + UUID.randomUUID() + ".test:" + server.getAddress().getPort() + "/inbox");
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FederationHttpClient loopbackClient() {
            return LoopbackClients.create();
        }
    }
}
