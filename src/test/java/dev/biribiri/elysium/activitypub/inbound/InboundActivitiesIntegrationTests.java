package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.boot.autoconfigure.Jackson3Serializer;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.moderation.DomainBlocks;
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
    private RepeatingHandler repeating;
    @Autowired
    private DomainBlocks blocks;
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
    void doesNotProcessAnActivityQueuedBeforeItsServerWasBlocked() {
        blocks.create("blocked-queue.test", null);
        var id = "https://blocked-queue.test/activities/" + UUID.randomUUID();
        var activity = new InboundActivity(id, "https://blocked-queue.test/actor", "Test", "{\"id\":\"" + id + "\"}");

        assertThat(inbound.accept(activity)).isTrue();

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
        assertThat(handler.handled(activity)).isZero();
    }

    @Test
    void finishesIfItsServerIsBlockedWhileItIsBeingProcessed() {
        var id = "https://blocked-during.test/activities/" + UUID.randomUUID();
        var activity = new InboundActivity(id, "https://blocked-during.test/actor", "Test", "{\"id\":\"" + id + "\"}");
        handler.onNext.put(activity.id(), ignored -> {
            blocks.create("blocked-during.test", null);
            throw new PermanentJobFailure("Won't fetch the actor: its server is blocked");
        });

        inbound.accept(activity);

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
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
    void doesNotDropARepeatThatArrivesWhileItsJobIsRunning() {
        var activity = activity("Repeating");
        repeating.onNext.put(activity.id(), ignored -> inbound.accept(again(activity)));

        inbound.accept(activity);

        await().atMost(WAIT).until(() -> repeating.handled(activity) == 2 && jobs(activity) == 0);
    }

    @Test
    void processesARepeatOfAnActivityThatWasSetAside() {
        var activity = activity("Repeating");
        repeating.onNext.put(activity.id(), ignored -> {
            throw new PermanentJobFailure("This activity's actor doesn't make sense");
        });
        inbound.accept(activity);
        await().atMost(WAIT).until(() -> failures(activity) == 1);

        inbound.accept(again(activity));

        // The first job stays set aside, for an admin to look at.
        await().atMost(WAIT).until(() -> repeating.handled(activity) == 2 && jobs(activity) == 1);
    }

    @Test
    void aRepeatIsNotAffectedByTheAgeOfAJobThatWasSetAside() {
        var activity = activity("Repeating");
        repeating.onNext.put(activity.id(), ignored -> {
            throw new PermanentJobFailure("This activity's actor doesn't make sense");
        });
        inbound.accept(activity);
        await().atMost(WAIT).until(() -> failures(activity) == 1);
        repeating.onNext.put(activity.id(), ignored -> {
            throw new IllegalStateException("A database was busy");
        });

        inbound.accept(again(activity));

        // It's retried in about a minute, rather than being set aside like the first one.
        await().atMost(WAIT).until(() -> soonestJob(activity).isBefore(Instant.now().plus(Duration.ofMinutes(5)))
                && repeating.handled(activity) == 2);
    }

    @Test
    void retryingAJobOldEnoughToHaveLostItsActivityFailsWithTheReason() {
        var activity = parkedAndGone();
        var instance = instanceOf(activity);
        jdbc.sql("UPDATE scheduled_tasks SET task_data = :data WHERE task_name = 'inbound-activity' AND task_instance = :instance")
                .param("data", new Jackson3Serializer().serialize(
                        new InboundJob(activity.id(), Instant.now().minus(Duration.ofDays(34)))))
                .param("instance", instance)
                .update();

        retryNow(activity);

        await().atMost(WAIT).until(() -> failures(activity) == 2);
        assertThat(jdbc.sql("""
                        SELECT exception_message FROM scheduled_execution_logs
                        WHERE task_name = 'inbound-activity' AND task_instance = :instance AND NOT succeeded
                        """)
                .param("instance", instance)
                .query(String.class)
                .list()).anyMatch(message -> message.contains("Its activity is gone"));
        assertThat(executionTime(activity)).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    @Test
    void retryingAYoungerJobWithNoActivityFinishesIt() {
        var activity = parkedAndGone();

        retryNow(activity);

        await().atMost(WAIT).until(() -> jobs(activity) == 0);
    }

    private InboundActivity parkedAndGone() {
        var activity = activity("Test");
        handler.onNext.put(activity.id(), ignored -> {
            throw new PermanentJobFailure("This can never work");
        });
        inbound.accept(activity);
        await().atMost(WAIT).until(() -> failures(activity) == 1);
        jdbc.sql("DELETE FROM inbound_activity WHERE activity_id = :id").param("id", activity.id()).update();
        return activity;
    }

    private String instanceOf(InboundActivity activity) {
        return jdbc.sql("""
                        SELECT task_instance FROM scheduled_tasks
                        WHERE task_name = 'inbound-activity' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activity.id() + "%")
                .query(String.class)
                .single();
    }

    private void retryNow(InboundActivity activity) {
        jdbc.sql("UPDATE scheduled_tasks SET execution_time = now() WHERE task_name = 'inbound-activity' AND task_instance = :instance")
                .param("instance", instanceOf(activity))
                .update();
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

    private static InboundActivity again(InboundActivity activity) {
        return new InboundActivity(activity.id(), activity.actorUri(), activity.type(), activity.body());
    }

    private Instant soonestJob(InboundActivity activity) {
        return jdbc.sql("""
                        SELECT min(execution_time) FROM scheduled_tasks
                        WHERE task_name = 'inbound-activity' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activity.id() + "%")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
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

    static class RepeatingHandler implements InboundActivityHandler {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, Consumer<InboundActivity>> onNext = new ConcurrentHashMap<>();

        @Override
        public String type() {
            return "Repeating";
        }

        @Override
        public boolean processesRepeats() {
            return true;
        }

        @Override
        public void handle(InboundActivity activity) {
            calls.add(activity.id());
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

        @Bean
        RepeatingHandler repeatingHandler() {
            return new RepeatingHandler();
        }
    }
}
