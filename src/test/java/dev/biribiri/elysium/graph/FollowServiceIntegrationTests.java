package dev.biribiri.elysium.graph;

import com.zaxxer.hikari.HikariDataSource;
import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FollowServiceIntegrationTests {

    @Autowired
    private FollowService follows;
    @Autowired
    private DataSource dataSource;

    @Test
    void readingFollowerInboxesOutsideATransactionGivesBackItsConnection() throws Exception {
        assertGivesBackConnections(follows::followerInboxes);
    }

    @Test
    void readingEarlierFollowerInboxesOutsideATransactionGivesBackItsConnection() throws Exception {
        assertGivesBackConnections(id -> follows.followerInboxesAsOf(id, Instant.now()));
    }

    private void assertGivesBackConnections(Consumer<UUID> read) throws Exception {
        read.accept(UUID.randomUUID());

        var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        await().atMost(Duration.ofSeconds(10)).until(() -> pool.getActiveConnections() == 0);
    }
}
