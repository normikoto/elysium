package dev.biribiri.elysium.activitypub.jobs;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The same configuration as {@code JobCleanupIntegrationTests}, so that they share a context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "db-scheduler.polling-interval=PT0.1S")
class ActivityCleanupIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private JdbcClient jdbc;

    @Test
    void removesOnlyWhatIsOldEnough() {
        activities("old", "35 days");
        activities("recent", "33 days");
        undoneFollow("cleanup-old", "35 days");
        undoneFollow("cleanup-recent", "33 days");
        host("old.test", "8 days");
        host("recent.test", "6 days");

        // Waits for the job to appear, which happens when the scheduler starts.
        await().atMost(WAIT).until(() -> jdbc.sql("""
                UPDATE scheduled_tasks SET execution_time = now()
                WHERE task_name = 'activity-cleanup' AND picked = false
                """).update() == 1);

        await().atMost(WAIT).until(() -> ids("SELECT host FROM federation_host WHERE host IN ('old.test', 'recent.test')")
                .equals(java.util.List.of("recent.test")));
        assertThat(ids("SELECT activity_id FROM outbound_activity WHERE activity_id LIKE 'cleanup-%'"))
                .containsExactly("cleanup-recent");
        assertThat(ids("SELECT activity_id FROM inbound_activity WHERE activity_id LIKE 'cleanup-%'"))
                .containsExactly("cleanup-recent");
        assertThat(ids("SELECT activity_uri FROM undone_follow WHERE activity_uri LIKE 'cleanup-%'"))
                .containsExactly("cleanup-recent");
        assertThat(ids("SELECT follower_uri FROM undone_follow_pair WHERE follower_uri LIKE 'cleanup-%'"))
                .containsExactly("cleanup-recent");
        assertThat(ids("SELECT host FROM federation_host WHERE host IN ('old.test', 'recent.test')"))
                .containsExactly("recent.test");
    }

    private java.util.List<String> ids(String sql) {
        return jdbc.sql(sql + " ORDER BY 1").query(String.class).list();
    }

    private void activities(String name, String age) {
        jdbc.sql("""
                        INSERT INTO outbound_activity (activity_id, actor_id, body, created_at)
                        VALUES (:id, :actor, '{}', now() - CAST(:age AS INTERVAL))
                        """)
                .param("id", "cleanup-" + name)
                .param("actor", UUID.randomUUID())
                .param("age", age)
                .update();
        jdbc.sql("""
                        INSERT INTO inbound_activity (activity_id, actor_uri, type, body, received_at)
                        VALUES (:id, 'https://a.test', 'Test', '{}', now() - CAST(:age AS INTERVAL))
                        """)
                .param("id", "cleanup-" + name)
                .param("age", age)
                .update();
    }

    private void undoneFollow(String uri, String age) {
        jdbc.sql("INSERT INTO undone_follow (activity_uri, follower_uri, undone_at) VALUES (:uri, 'https://a.test', now() - CAST(:age AS INTERVAL))")
                .param("uri", uri)
                .param("age", age)
                .update();
        var actor = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri)
                        VALUES (:id, :uri, 'cleanup', :uri, :uri)
                        """)
                .param("id", actor)
                .param("uri", "https://cleanup.test/" + actor)
                .update();
        jdbc.sql("""
                        INSERT INTO undone_follow_pair (follower_uri, followed_id, undone_at)
                        VALUES (:uri, :actor, now() - CAST(:age AS INTERVAL))
                        """)
                .param("uri", uri)
                .param("actor", actor)
                .param("age", age)
                .update();
    }

    private void host(String host, String age) {
        jdbc.sql("""
                        INSERT INTO federation_host (host, consecutive_failures, retry_after)
                        VALUES (:host, 1, now() - CAST(:age AS INTERVAL))
                        """)
                .param("host", host)
                .param("age", age)
                .update();
    }
}
