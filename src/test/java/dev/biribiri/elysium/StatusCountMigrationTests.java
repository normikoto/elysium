package dev.biribiri.elysium;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the migrations in their own schema, so that they can stop at the version before the counts existed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StatusCountMigrationTests {

    private static final String SCHEMA = "status_count_migration";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void countsExistingStatusesWithoutDirectOnes() {
        flyway("4").migrate();
        var alice = insertActor("alice");
        var bob = insertActor("bob");
        var carol = insertActor("carol");
        insertStatus(alice, "public");
        insertStatus(alice, "unlisted");
        insertStatus(alice, "private");
        insertStatus(alice, "direct");
        insertStatus(bob, "direct");
        insertStatus(carol, "public");

        flyway("5").migrate();

        assertThat(count(alice)).isEqualTo(3);
        assertThat(count(bob)).isZero();
        assertThat(count(carol)).isEqualTo(1);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }

    private UUID insertActor(String username) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO %s.actor (id, uri, username, domain, inbox_uri, outbox_uri, public_key_pem)
                        VALUES (:id, :uri, :username, 'elysium.example', 'inbox', 'outbox', 'key')
                        """.formatted(SCHEMA))
                .param("id", id)
                .param("uri", "https://elysium.example/users/" + username)
                .param("username", username)
                .update();
        return id;
    }

    private void insertStatus(UUID actorId, String visibility) {
        jdbc.sql("""
                        INSERT INTO %s.status (id, uri, actor_id, text, content, visibility, published_at)
                        VALUES (:id, :uri, :actorId, 'x', '<p>x</p>', :visibility, now())
                        """.formatted(SCHEMA))
                .param("id", UUID.randomUUID())
                .param("uri", "https://elysium.example/" + UUID.randomUUID())
                .param("actorId", actorId)
                .param("visibility", visibility)
                .update();
    }

    private long count(UUID actorId) {
        return jdbc.sql("SELECT statuses_count FROM " + SCHEMA + ".actor_status_count WHERE actor_id = :id")
                .param("id", actorId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }
}
