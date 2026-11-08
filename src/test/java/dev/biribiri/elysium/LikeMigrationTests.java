package dev.biribiri.elysium;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LikeMigrationTests {
    private static final String SCHEMA = "like_migration";
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
    void preservesExistingNotificationsAndCascadesFavouriteSources() {
        migrate("22");
        var author = UUID.randomUUID();
        var sender = UUID.randomUUID();
        for (var id : new UUID[]{author, sender}) {
            jdbc.sql("INSERT INTO " + SCHEMA + ".actor (id, uri, username, domain, inbox_uri, is_local) "
                            + "VALUES (:id, :uri, :name, 'migration.test', :uri, true)")
                    .param("id", id).param("uri", "https://migration.test/users/" + id).param("name", id.toString()).update();
        }
        var status = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".status (id, uri, actor_id, text, content, visibility, published_at) "
                        + "VALUES (:id, :uri, :author, 'post', '<p>post</p>', 'public', now())")
                .param("id", status).param("uri", "https://migration.test/statuses/" + status).param("author", author).update();
        jdbc.sql("INSERT INTO " + SCHEMA + ".inbox_notification "
                        + "(id, recipient_id, sender_id, type, source_id, status_id, created_at, dismissed) "
                        + "VALUES (:id, :author, :sender, 'mention', :status, :status, now(), true)")
                .param("id", UUID.randomUUID()).param("author", author).param("sender", sender).param("status", status).update();

        migrate("23");

        assertThat(jdbc.sql("SELECT dismissed FROM " + SCHEMA + ".inbox_notification").query(Boolean.class).single()).isTrue();
        var like = UUID.randomUUID();
        insertLike(like, sender, status);
        assertThatThrownBy(() -> insertLike(UUID.randomUUID(), sender, status))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.sql("INSERT INTO " + SCHEMA + ".inbox_notification "
                        + "(id, recipient_id, sender_id, type, source_id, status_id, like_id, created_at) "
                        + "VALUES (:id, :author, :sender, 'favourite', :like, :status, :like, now())")
                .param("id", UUID.randomUUID()).param("author", author).param("sender", sender)
                .param("like", like).param("status", status).update();
        jdbc.sql("DELETE FROM " + SCHEMA + ".status_like WHERE id = :id").param("id", like).update();
        assertThat(jdbc.sql("SELECT type FROM " + SCHEMA + ".inbox_notification").query(String.class).single()).isEqualTo("mention");
        insertLike(UUID.randomUUID(), sender, status);
        jdbc.sql("DELETE FROM " + SCHEMA + ".status WHERE id = :id").param("id", status).update();
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".status_like").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".inbox_notification").query(Long.class).single()).isZero();
    }

    private void insertLike(UUID id, UUID sender, UUID status) {
        jdbc.sql("INSERT INTO " + SCHEMA + ".status_like VALUES (:id, :sender, :status, :uri, now())")
                .param("id", id).param("sender", sender).param("status", status)
                .param("uri", "https://migration.test/likes/" + id).update();
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load().migrate();
    }
}
