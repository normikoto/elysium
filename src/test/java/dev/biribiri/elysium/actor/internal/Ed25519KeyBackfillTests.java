package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the migrations in their own schema, so that they can stop at the version before accounts had Ed25519 keys.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class Ed25519KeyBackfillTests {

    private static final String SCHEMA = "ed25519_key_backfill";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void givesExistingAccountsAndTheInstanceActorTheirOwnKeys() {
        flyway("67").migrate();
        var alice = insertAccount("alice");
        var bob = insertAccount("bob");
        jdbc.sql("INSERT INTO %s.instance_actor (public_key_pem, private_key_pem) VALUES ('rsa', 'rsa')".formatted(SCHEMA))
                .update();

        flyway("68").migrate();

        var aliceKeys = accountKeys(alice);
        var bobKeys = accountKeys(bob);
        var instanceKeys = jdbc.sql("""
                        SELECT ed25519_public_key_pem, ed25519_private_key_pem FROM %s.instance_actor
                        """.formatted(SCHEMA))
                .query((row, n) -> new Ed25519Keys(row.getString(1), row.getString(2)))
                .single();
        assertThat(Ed25519Keys.parsePrivateKey(aliceKeys.privateKeyPem())).isNotNull();
        assertThat(Ed25519Keys.parsePrivateKey(instanceKeys.privateKeyPem())).isNotNull();
        assertThat(aliceKeys.publicKeyPem()).isNotEqualTo(bobKeys.publicKeyPem())
                .isNotEqualTo(instanceKeys.publicKeyPem());
        assertThat(jdbc.sql("SELECT private_key_pem FROM %s.local_account WHERE actor_id = :id".formatted(SCHEMA))
                .param("id", alice)
                .query(String.class)
                .single()).isEqualTo("rsa");
    }

    @Test
    void worksBeforeTheInstanceActorExists() {
        flyway("67").migrate();

        flyway("68").migrate();

        assertThat(jdbc.sql("SELECT count(*) FROM %s.instance_actor".formatted(SCHEMA)).query(Long.class).single())
                .isZero();
    }

    @Test
    void newAccountsNeedAKey() {
        flyway("68").migrate();
        var id = UUID.randomUUID();
        insertActor(id, "carol");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO %s.local_account (actor_id, private_key_pem) VALUES (:id, 'rsa')
                        """.formatted(SCHEMA))
                .param("id", id)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertAccount(String username) {
        var id = UUID.randomUUID();
        insertActor(id, username);
        jdbc.sql("INSERT INTO %s.local_account (actor_id, private_key_pem) VALUES (:id, 'rsa')".formatted(SCHEMA))
                .param("id", id)
                .update();
        return id;
    }

    private void insertActor(UUID id, String username) {
        jdbc.sql("""
                        INSERT INTO %s.actor (id, uri, username, domain, inbox_uri, outbox_uri, public_key_pem, is_local)
                        VALUES (:id, :uri, :username, 'elysium.example', 'inbox', 'outbox', 'rsa', TRUE)
                        """.formatted(SCHEMA))
                .param("id", id)
                .param("uri", "https://elysium.example/users/" + username)
                .param("username", username)
                .update();
    }

    private Ed25519Keys accountKeys(UUID id) {
        return jdbc.sql("""
                        SELECT ed25519_public_key_pem, ed25519_private_key_pem FROM %s.local_account WHERE actor_id = :id
                        """.formatted(SCHEMA))
                .param("id", id)
                .query((row, n) -> new Ed25519Keys(row.getString(1), row.getString(2)))
                .single();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .javaMigrations(new Ed25519KeyBackfill())
                .target(target)
                .load();
    }
}
