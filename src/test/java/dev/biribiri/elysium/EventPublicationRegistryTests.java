package dev.biribiri.elysium;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import({TestcontainersConfiguration.class, EventPublicationRegistryTests.ListenerConfiguration.class})
@SpringBootTest
class EventPublicationRegistryTests {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RecordingListener listener;

    @Test
    void completesPublicationAfterListenerRuns() {
        var event = new SampleEvent(UUID.randomUUID());

        transactions.executeWithoutResult(status -> events.publishEvent(event));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(listener.received()).contains(event);
            assertThat(completedPublications(event)).isOne();
        });
    }

    private long completedPublications(SampleEvent event) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication
                        WHERE completion_date IS NOT NULL AND serialized_event LIKE :id
                        """)
                .param("id", "%" + event.id() + "%")
                .query(Long.class)
                .single();
    }

    public record SampleEvent(UUID id) {
    }

    public static class RecordingListener {
        private final Set<SampleEvent> received = ConcurrentHashMap.newKeySet();

        @ApplicationModuleListener
        public void on(SampleEvent event) {
            received.add(event);
        }

        // The bean is proxied, so its state has to be read through a method.
        public Set<SampleEvent> received() {
            return received;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }
}
