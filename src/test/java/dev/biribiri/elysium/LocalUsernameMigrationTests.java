package dev.biribiri.elysium;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the migrations in their own schema, so that they can stop at the version before only local usernames were unique.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LocalUsernameMigrationTests {

    private static final String SCHEMA = "local_username_migration";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void existingAccountsKeepTheirUsernames() {
        flyway("8").migrate();
        var actorId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO %s.actor (id, uri, username, domain, inbox_uri, outbox_uri, public_key_pem)
                        VALUES (:id, 'https://elysium.example/users/alice', 'alice', 'elysium.example', 'inbox', 'outbox',
                                'key')
                        """.formatted(SCHEMA))
                .param("id", actorId)
                .update();
        jdbc.sql("INSERT INTO %s.local_account (actor_id, private_key_pem) VALUES (:id, 'key')".formatted(SCHEMA))
                .param("id", actorId)
                .update();

        flyway("9").migrate();

        assertThat(jdbc.sql("SELECT is_local FROM %s.actor WHERE id = :id".formatted(SCHEMA))
                .param("id", actorId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThatThrownBy(() -> insertActor("https://elysium.example/users/ALICE", "ALICE", true))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void remoteActorsCanShareAUsername() {
        flyway("9").migrate();

        insertActor("https://remote.example/users/1", "bob", false);
        insertActor("https://remote.example/users/2", "BOB", false);

        assertThat(jdbc.sql("SELECT count(*) FROM %s.actor".formatted(SCHEMA)).query(Long.class).single()).isEqualTo(2);
    }

    private void insertActor(String uri, String username, boolean local) {
        jdbc.sql("""
                        INSERT INTO %s.actor (id, uri, username, domain, inbox_uri, is_local)
                        VALUES (:id, :uri, :username, 'elysium.example', 'inbox', :local)
                        """.formatted(SCHEMA))
                .param("id", UUID.randomUUID())
                .param("uri", uri)
                .param("username", username)
                .param("local", local)
                .update();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }
}
