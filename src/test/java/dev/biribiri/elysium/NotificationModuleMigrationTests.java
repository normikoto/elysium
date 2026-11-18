package dev.biribiri.elysium;

import dev.biribiri.elysium.content.StatusStored;
import dev.biribiri.elysium.notification.NotificationAdded;
import dev.biribiri.elysium.notification.Notifier;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Events published before the inbox module was renamed can still be retried, and its tables lose the inbox prefix. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NotificationModuleMigrationTests {

    private static final String SCHEMA = "notification_module_migration";

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
    void storedPublicationsNameTheRenamedClasses() throws NoSuchMethodException {
        migrate("56");
        var projected = insert("dev.biribiri.elysium.inbox.InboxProjection.on(dev.biribiri.elysium.content.StatusStored)",
                "dev.biribiri.elysium.content.StatusStored");
        var streamed = insert("dev.biribiri.elysium.api.streaming.StreamEvents.on(dev.biribiri.elysium.inbox.NotificationAdded)",
                "dev.biribiri.elysium.inbox.NotificationAdded");
        var unrelated = insert("dev.biribiri.elysium.activitypub.outbound.Deliveries.on(dev.biribiri.elysium.content.StatusStored)",
                "dev.biribiri.elysium.content.StatusStored");

        migrate("57");

        assertThat(row(projected)).containsExactly(
                Notifier.class.getName() + ".on(" + StatusStored.class.getName() + ")",
                StatusStored.class.getName());
        assertThat(row(streamed)).containsExactly(
                "dev.biribiri.elysium.api.streaming.StreamEvents.on(" + NotificationAdded.class.getName() + ")",
                NotificationAdded.class.getName());
        assertThat(row(unrelated)).containsExactly(
                "dev.biribiri.elysium.activitypub.outbound.Deliveries.on(dev.biribiri.elysium.content.StatusStored)",
                "dev.biribiri.elysium.content.StatusStored");
        Notifier.class.getMethod("on", StatusStored.class);
    }

    @Test
    void tablesAndTheirConstraintsAndIndexesLoseTheInboxPrefix() {
        migrate("56");
        var owner = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".actor (id, uri, username, domain, inbox_uri, is_local) "
                        + "VALUES (:id, :uri, 'remote', 'migration.test', :uri, false)")
                .param("id", owner).param("uri", "https://migration.test/users/" + owner).update();
        var read = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".inbox_marker (owner_id, timeline, last_read_id) VALUES (:owner, 'home', :read)")
                .param("owner", owner).param("read", read).update();

        migrate("57");

        assertThat(jdbc.sql("SELECT last_read_id FROM " + SCHEMA + ".marker WHERE owner_id = :owner")
                .param("owner", owner).query(UUID.class).single()).isEqualTo(read);
        assertThat(jdbc.sql("SELECT relname FROM pg_class WHERE relnamespace = CAST(:schema AS regnamespace) "
                        + "AND relname LIKE 'inbox\\_%'")
                .param("schema", SCHEMA).query(String.class).list()).isEmpty();
        assertThat(jdbc.sql("SELECT conname FROM pg_constraint WHERE connamespace = CAST(:schema AS regnamespace) "
                        + "AND conname LIKE 'inbox\\_%'")
                .param("schema", SCHEMA).query(String.class).list()).isEmpty();
        assertThat(jdbc.sql("SELECT relname FROM pg_class WHERE relnamespace = CAST(:schema AS regnamespace) AND relkind = 'r' "
                        + "AND relname IN ('notification', 'notification_follow_group', 'notification_sequence', "
                        + "'conversation', 'conversation_status', 'marker')")
                .param("schema", SCHEMA).query(String.class).list()).hasSize(6);
    }

    private UUID insert(String listener, String eventType) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".event_publication (id, listener_id, event_type, serialized_event, publication_date) "
                        + "VALUES (:id, :listener, :type, '{}', now())")
                .param("id", id).param("listener", listener).param("type", eventType).update();
        return id;
    }

    private List<String> row(UUID id) {
        return jdbc.sql("SELECT listener_id, event_type FROM " + SCHEMA + ".event_publication WHERE id = :id")
                .param("id", id).query((rs, n) -> List.of(rs.getString(1), rs.getString(2))).single();
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load().migrate();
    }
}
