package dev.biribiri.elysium.jobs;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "db-scheduler.polling-interval=PT0.1S")
class JobCleanupIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private JdbcClient jdbc;

    @Test
    void removesOnlyWhatIsOldEnough() {
        history(1, true, "8 days");
        history(2, true, "6 days");
        history(3, false, "31 days");
        history(4, false, "29 days");

        // Waits for the job to appear, which happens when the scheduler starts.
        await().atMost(WAIT).until(() -> jdbc.sql("""
                UPDATE scheduled_tasks SET execution_time = now()
                WHERE task_name = 'job-cleanup' AND picked = false
                """).update() == 1);

        await().atMost(WAIT).until(() -> ids("SELECT id::text FROM scheduled_execution_logs WHERE id IN (1, 2, 3, 4)")
                .equals(java.util.List.of("2", "4")));
    }

    private java.util.List<String> ids(String sql) {
        return jdbc.sql(sql + " ORDER BY 1").query(String.class).list();
    }

    private void history(long id, boolean succeeded, String age) {
        jdbc.sql("""
                        INSERT INTO scheduled_execution_logs
                            (id, task_name, task_instance, time_started, time_finished, succeeded, duration_ms)
                        VALUES (:id, 'test', 'test', now() - CAST(:age AS INTERVAL), now() - CAST(:age AS INTERVAL), :succeeded, 0)
                        """)
                .param("id", id)
                .param("age", age)
                .param("succeeded", succeeded)
                .update();
    }
}
