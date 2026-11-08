package dev.biribiri.elysium;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoostMigrationTests {
    private static final String SCHEMA = "boost_migration";
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;
    private Connection connection;

    @BeforeEach
    void useMigrationSchema() throws SQLException {
        connection = dataSource.getConnection();
        connection.setSchema(SCHEMA);
        jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    @AfterEach
    void dropSchema() throws SQLException {
        try {
            jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
        } finally {
            connection.setSchema("public");
            connection.close();
        }
    }

    @Test
    void keepsExistingNotificationsAndRemovesBoostsWithWhatTheyBoost() {
        migrate("23");
        var author = actor();
        var booster = actor();
        var status = status(author, null);
        jdbc.sql("INSERT INTO " + SCHEMA + ".inbox_notification "
                        + "(id, recipient_id, sender_id, type, source_id, status_id, created_at) "
                        + "VALUES (:id, :author, :sender, 'mention', :status, :status, now())")
                .param("id", UUID.randomUUID()).param("author", author).param("sender", booster).param("status", status)
                .update();

        migrate("24");

        var boost = status(booster, status);
        assertThatThrownBy(() -> status(booster, status)).isInstanceOf(DuplicateKeyException.class);
        jdbc.sql("INSERT INTO " + SCHEMA + ".inbox_notification "
                        + "(id, recipient_id, sender_id, type, source_id, status_id, reblog_id, created_at) "
                        + "VALUES (:id, :author, :sender, 'reblog', :boost, :status, :boost, now())")
                .param("id", UUID.randomUUID()).param("author", author).param("sender", booster)
                .param("boost", boost).param("status", status).update();
        jdbc.sql("DELETE FROM " + SCHEMA + ".status WHERE id = :id").param("id", boost).update();
        assertThat(jdbc.sql("SELECT type FROM " + SCHEMA + ".inbox_notification").query(String.class).single())
                .isEqualTo("mention");

        status(booster, status);
        jdbc.sql("DELETE FROM " + SCHEMA + ".status WHERE id = :id").param("id", status).update();
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".status").query(Long.class).single()).isZero();
    }

    private UUID actor() {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".actor (id, uri, username, domain, inbox_uri, is_local) "
                        + "VALUES (:id, :uri, :name, 'migration.test', :uri, true)")
                .param("id", id).param("uri", "https://migration.test/users/" + id).param("name", id.toString()).update();
        return id;
    }

    private UUID status(UUID author, UUID reblogOf) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".status (id, uri, actor_id, text, content, visibility, published_at"
                        + (reblogOf == null ? "" : ", reblog_of_id") + ") VALUES (:id, :uri, :author, '', '', 'public', now()"
                        + (reblogOf == null ? "" : ", :reblogOf") + ")")
                .param("id", id).param("uri", "https://migration.test/statuses/" + id).param("author", author)
                .params(reblogOf == null ? java.util.Map.of() : java.util.Map.of("reblogOf", reblogOf)).update();
        return id;
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load().migrate();
    }
}
