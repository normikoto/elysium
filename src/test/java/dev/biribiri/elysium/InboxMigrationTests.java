package dev.biribiri.elysium;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InboxMigrationTests {
    private static final String SCHEMA = "inbox_migration";
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void backfillsDirectThreadsAsReadWithoutHistoricalNotifications() {
        flyway("21").migrate();
        var alice = actor("alice", true);
        var bob = actor("bob", true);
        var remote = actor("remote", false);
        var first = status(alice, null, null);
        mention(first, bob, false);
        var reply = status(bob, first, null);
        mention(reply, alice, false);
        var separate = status(alice, null, null);
        mention(separate, bob, false);
        var silent = status(remote, null, null);
        mention(silent, bob, true);

        flyway("22").migrate();

        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".inbox_conversation").query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".inbox_conversation_status").query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".inbox_conversation WHERE last_read_id IS NULL")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".inbox_notification").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(DISTINCT thread_id) FROM " + SCHEMA + ".status WHERE id IN (:ids)")
                .param("ids", List.of(first, reply)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void joinsMissingParentsAndTerminatesOnCyclicRemoteReplies() {
        flyway("21").migrate();
        var bob = actor("bob", true);
        var remote = actor("remote", false);
        var first = status(remote, null, "https://remote.example/cycle-two");
        jdbc.sql("UPDATE " + SCHEMA + ".status SET uri = 'https://remote.example/cycle-one' WHERE id = :id")
                .param("id", first).update();
        mention(first, bob, false);
        var second = status(remote, null, "https://remote.example/cycle-one");
        jdbc.sql("UPDATE " + SCHEMA + ".status SET uri = 'https://remote.example/cycle-two' WHERE id = :id")
                .param("id", second).update();
        mention(second, bob, false);
        var sibling = status(remote, null, "https://remote.example/unknown");
        var siblingTwo = status(remote, null, "https://remote.example/unknown");
        mention(sibling, bob, false);
        mention(siblingTwo, bob, false);
        flyway("22").migrate();
        assertThat(jdbc.sql("SELECT count(DISTINCT thread_id) FROM " + SCHEMA + ".status").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".inbox_conversation WHERE owner_id = :id")
                .param("id", bob).query(Long.class).single()).isEqualTo(2);
    }

    private UUID actor(String name, boolean local) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".actor (id, uri, username, domain, inbox_uri, is_local) "
                        + "VALUES (:id, :uri, :name, :domain, :inbox, :local)")
                .param("id", id).param("uri", "https://" + name + ".example/users/" + name).param("name", name)
                .param("domain", name + ".example").param("inbox", "https://" + name + ".example/inbox").param("local", local).update();
        return id;
    }

    private UUID status(UUID author, UUID parent, String parentUri) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".status (id, uri, actor_id, text, content, visibility, published_at, in_reply_to_id, in_reply_to_uri) "
                        + "VALUES (:id, :uri, :author, 'message', '<p>message</p>', 'direct', now(), :parent, :parentUri)")
                .param("id", id).param("uri", "https://remote.example/" + id).param("author", author)
                .param("parent", parent, Types.OTHER).param("parentUri", parentUri, Types.VARCHAR).update();
        return id;
    }

    private void mention(UUID status, UUID actor, boolean silent) {
        jdbc.sql("INSERT INTO " + SCHEMA + ".status_mention VALUES (:status, :actor, :silent)")
                .param("status", status).param("actor", actor).param("silent", silent).update();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }
}
