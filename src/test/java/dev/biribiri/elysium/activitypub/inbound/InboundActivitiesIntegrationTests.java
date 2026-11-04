package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

@Import({TestcontainersConfiguration.class, InboundActivitiesIntegrationTests.Config.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class InboundActivitiesIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private InboundActivities inbound;
    @Autowired
    private RecordingHandler handler;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void processesAnActivityOutsideAnyTransaction() {
        var activity = activity("Test");

        assertThat(inbound.accept(activity)).isTrue();

        await().atMost(WAIT).until(() -> handler.handled(activity) == 1 && jobs(activity) == 0);
        assertThat(handler.inTransaction).isEmpty();
    }

    @Test
    void doesNotProcessARepeatOfAnActivity() {
        var activity = activity("Test");

        assertThat(inbound.accept(activity)).isTrue();
        assertThat(inbound.accept(activity)).isFalse();
        await().atMost(WAIT).until(() -> handler.handled(activity) == 1 && jobs(activity) == 0);

        assertThat(inbound.accept(activity)).isFalse();
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> handler.handled(activity) == 1);
    }

    @Test
    void retriesATemporaryFailure() {
        var activity = activity("Test");
        handler.onNext.put(activity.id(), ignored -> {
            throw new IllegalStateException("A database was busy");
        });

        inbound.accept(activity);
        await().atMost(WAIT).until(() -> failures(activity) == 1);

        assertThat(Duration.between(Instant.now(), executionTime(activity)))
                .isBetween(Duration.ofSeconds(30), Duration.ofSeconds(80));
        jdbc.sql("UPDATE scheduled_tasks SET execution_time = now() WHERE convert_from(task_data, 'UTF8') LIKE :id")
                .param("id", "%" + activity.id() + "%")
                .update();

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(handler.handled(activity)).isEqualTo(2);
    }

    @Test
    void parksAnActivityThatCanNeverBeProcessed() {
        var activity = activity("Test");
        handler.onNext.put(activity.id(), ignored -> {
            throw new PermanentJobFailure("This activity doesn't make sense");
        });

        inbound.accept(activity);
        await().atMost(WAIT).until(() -> failures(activity) == 1);

        assertThat(executionTime(activity)).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(Duration.ofMinutes(1)));
    }

    @Test
    void ignoresAnActivityWithNoHandler() {
        var activity = activity("SomethingElse");

        inbound.accept(activity);

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(failures(activity)).isZero();
    }

    @Test
    void refusesAnIdTooLongToStore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InboundActivity("x".repeat(2049), "https://a.test/actor", "Test", "{}"));
    }

    @Test
    void acceptsTheLongestAllowedId() {
        var activity = new InboundActivity("x".repeat(2048 - 36) + UUID.randomUUID(), "https://a.test/actor", "Test", "{}");

        assertThat(inbound.accept(activity)).isTrue();
    }

    private static InboundActivity activity(String type) {
        var id = "https://remote.test/activities/" + UUID.randomUUID();
        return new InboundActivity(id, "https://remote.test/actor", type, "{\"id\":\"" + id + "\"}");
    }

    private long jobs(InboundActivity activity) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'inbound-activity' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activity.id() + "%")
                .query(Long.class)
                .single();
    }

    private int failures(InboundActivity activity) {
        return jdbc.sql("""
                        SELECT consecutive_failures FROM scheduled_tasks
                        WHERE task_name = 'inbound-activity' AND picked = false
                          AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activity.id() + "%")
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    private Instant executionTime(InboundActivity activity) {
        return jdbc.sql("SELECT execution_time FROM scheduled_tasks WHERE convert_from(task_data, 'UTF8') LIKE :id")
                .param("id", "%" + activity.id() + "%")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    static class RecordingHandler implements InboundActivityHandler {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> inTransaction = new CopyOnWriteArrayList<>();
        final Map<String, Consumer<InboundActivity>> onNext = new ConcurrentHashMap<>();

        @Override
        public String type() {
            return "Test";
        }

        @Override
        public void handle(InboundActivity activity) {
            calls.add(activity.id());
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                inTransaction.add(activity.id());
            }
            var next = onNext.remove(activity.id());
            if (next != null) {
                next.accept(activity);
            }
        }

        long handled(InboundActivity activity) {
            return calls.stream().filter(activity.id()::equals).count();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }
}
