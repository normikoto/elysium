package dev.biribiri.elysium;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attachments whose copies were pruned before V55 are downloaded again through the proxy, and ones that were given up
 * stay links.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RefetchPrunedAttachmentsMigrationTests {

    private static final String SCHEMA = "refetch_pruned_migration";

    @Autowired
    DataSource dataSource;
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
    void onlyPrunedCopiesGoBackToNotBeingTried() {
        migrate("54");
        var author = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".actor (id, uri, username, domain, inbox_uri, is_local) "
                        + "VALUES (:id, :uri, 'remote', 'migration.test', :uri, false)")
                .param("id", author).param("uri", "https://migration.test/users/" + author).update();
        var status = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".status (id, uri, actor_id, text, content, visibility, published_at) "
                        + "VALUES (:id, :uri, :author, 'post', '<p>post</p>', 'public', now())")
                .param("id", status).param("uri", "https://migration.test/statuses/" + status).param("author", author)
                .update();
        var pruned = insert(author, status, null, 1234L, true, false);
        var givenUp = insert(author, status, null, null, true, false);
        var kept = insert(author, status, "kept.webp", 1234L, true, false);
        var waiting = insert(author, status, null, null, false, false);
        var rejectedAfterACopy = insert(author, status, null, 1234L, true, true);

        migrate("55");

        assertThat(cachedAt(pruned)).isFalse();
        assertThat(cachedAt(givenUp)).isTrue();
        assertThat(cachedAt(kept)).isTrue();
        assertThat(cachedAt(waiting)).isFalse();
        // Still rejected, so it isn't downloaded, but it can be once its media is allowed again.
        assertThat(cachedAt(rejectedAfterACopy)).isFalse();
    }

    private UUID insert(UUID author, UUID status, String fileName, Long fileSize, boolean tried, boolean rejected) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".media_attachment "
                        + "(id, account_id, status_id, type, file_name, file_size, remote_url, rejected, cached_at, created_at) "
                        + "VALUES (:id, :author, :status, 'image', :fileName, :fileSize, :url, :rejected, "
                        + (tried ? "now() - interval '40 days'" : "NULL") + ", now())")
                .param("id", id).param("author", author).param("status", status).param("fileName", fileName)
                .param("fileSize", fileSize, Types.BIGINT)
                .param("url", "https://migration.test/files/" + id + ".png").param("rejected", rejected)
                .update();
        return id;
    }

    private boolean cachedAt(UUID id) {
        return jdbc.sql("SELECT cached_at IS NOT NULL FROM " + SCHEMA + ".media_attachment WHERE id = :id")
                .param("id", id).query(Boolean.class).single();
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load().migrate();
    }
}
