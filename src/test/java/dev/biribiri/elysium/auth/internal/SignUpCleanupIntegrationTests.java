package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * The same configuration as {@code JobCleanupIntegrationTests}, so that they share a context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "db-scheduler.polling-interval=PT0.1S")
class SignUpCleanupIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private JdbcClient jdbc;

    @Test
    void removesOnlyExpiredUnconfirmedSignUps() {
        pending("cleanupexpired", "25 hours", false);
        pending("cleanuprecent", "23 hours", false);
        pending("cleanupconfirmed", "30 days", true);

        // Waits for the job to appear, which happens when the scheduler starts.
        await().atMost(WAIT).until(() -> jdbc.sql("""
                UPDATE scheduled_tasks SET execution_time = now()
                WHERE task_name = :name AND picked = false
                """).param("name", SignUpCleanupTask.NAME).update() == 1);

        await().atMost(WAIT).until(() -> usernames().equals(List.of("cleanupconfirmed", "cleanuprecent")));
    }

    private List<String> usernames() {
        return jdbc.sql("SELECT username FROM pending_sign_up WHERE username LIKE 'cleanup%' ORDER BY 1")
                .query(String.class)
                .list();
    }

    private void pending(String username, String age, boolean confirmed) {
        jdbc.sql("""
                        INSERT INTO pending_sign_up
                            (id, username, email, password_hash, verification_token_hash, verified_at, created_at)
                        VALUES (:id, :username, :email, 'hash', :token,
                                CASE WHEN :confirmed THEN now() END, now() - CAST(:age AS INTERVAL))
                        """)
                .param("id", UUID.randomUUID())
                .param("username", username)
                .param("email", username + "@mail.example")
                .param("token", confirmed ? null : UUID.randomUUID().toString())
                .param("confirmed", confirmed)
                .param("age", age)
                .update();
    }
}
