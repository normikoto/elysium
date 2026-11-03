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
 * Runs the migrations in their own schema, so that they can stop at the version before roles existed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountRoleMigrationTests {

    private static final String SCHEMA = "account_role_migration";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void existingAccountsStayRegularUsers() {
        flyway("5").migrate();
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
        jdbc.sql("INSERT INTO %s.account_credential (actor_id, password_hash) VALUES (:id, 'hash')".formatted(SCHEMA))
                .param("id", actorId)
                .update();

        flyway("6").migrate();

        assertThat(jdbc.sql("SELECT role FROM %s.account_credential WHERE actor_id = :id".formatted(SCHEMA))
                .param("id", actorId)
                .query(String.class)
                .single()).isEqualTo("USER");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }
}
