package dev.biribiri.elysium;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.awaitility.Awaitility.await;

/**
 * Waits for the event listeners to finish with what a test class published. Test classes share contexts, and so databases,
 * and some leave publications that are never completed, such as those whose listener a test made fail. Waiting for every
 * publication would then time out, depending on which classes ran first, so this only counts those since the class started.
 */
public final class TestEvents {

    private TestEvents() {
    }

    public static void awaitHandled(JdbcClient jdbc, Instant since) {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND publication_date >= :since
                        """)
                .param("since", since.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single() == 0);
    }
}
