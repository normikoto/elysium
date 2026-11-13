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

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuthorizedClientMigrationTests {

    private static final String SCHEMA = "authorized_client_migration";

    @Autowired
    private DataSource dataSource;

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
    void marksClientsWithATokenOrAConsentAsAuthorized() {
        migrate("39");
        for (var id : new String[]{"token", "consent", "code", "unused"}) {
            jdbc.sql("INSERT INTO " + SCHEMA + ".oauth2_registered_client "
                            + "(id, client_id, client_name, client_authentication_methods, authorization_grant_types, "
                            + "scopes, client_settings, token_settings) "
                            + "VALUES (:id, :id, :id, 'client_secret_basic', 'authorization_code', 'read', '{}', '{}')")
                    .param("id", id)
                    .update();
        }
        insertAuthorization("token", "a token");
        insertAuthorization("code", null);
        jdbc.sql("INSERT INTO " + SCHEMA + ".oauth2_authorization_consent VALUES ('consent', 'someone', 'SCOPE_read')")
                .update();

        migrate("40");

        assertThat(jdbc.sql("SELECT registered_client_id FROM " + SCHEMA + ".authorized_client ORDER BY 1")
                .query(String.class)
                .list()).containsExactly("consent", "token");
    }

    private void insertAuthorization(String clientId, String accessToken) {
        jdbc.sql("INSERT INTO " + SCHEMA + ".oauth2_authorization "
                        + "(id, registered_client_id, principal_name, authorization_grant_type, access_token_value) "
                        + "VALUES (:id, :client, 'someone', 'authorization_code', :token)")
                .param("id", clientId + "-authorization")
                .param("client", clientId)
                .param("token", accessToken)
                .update();
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load().migrate();
    }
}
