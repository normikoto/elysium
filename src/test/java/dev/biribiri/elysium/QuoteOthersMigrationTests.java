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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose quotes were reviewed becomes whose are approved automatically and what happens to everyone else's, without
 * approving anything automatically that wasn't before.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuoteOthersMigrationTests {

    private static final String SCHEMA = "quote_others_migration";

    @Autowired
    DataSource dataSource;
    @Autowired
    JsonMapper json;
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
    void postingDefaultsBecomeThePolicyAndOthersThatApproveNoMore() {
        migrate("58");
        var publicNone = defaults("public", "none");
        var publicNonFollowers = defaults("public", "non_followers");
        var publicAll = defaults("public", "all");
        var followersNone = defaults("followers", "none");
        var followersNonFollowers = defaults("followers", "non_followers");
        var followersAll = defaults("followers", "all");
        var nobodyAll = defaults("nobody", "all");

        migrate("59");

        assertThat(defaults(publicNone)).containsExactly("public", "reject");
        assertThat(defaults(publicNonFollowers)).containsExactly("followers", "review");
        assertThat(defaults(publicAll)).containsExactly("nobody", "review");
        assertThat(defaults(followersNone)).containsExactly("followers", "reject");
        assertThat(defaults(followersNonFollowers)).containsExactly("followers", "reject");
        assertThat(defaults(followersAll)).containsExactly("nobody", "review");
        assertThat(defaults(nobodyAll)).containsExactly("nobody", "reject");
    }

    @Test
    void scheduledDraftsThatChoseEitherHaveBothWrittenOut() {
        migrate("58");
        var author = defaults("public", "non_followers");
        var leftToDefaults = draft(author, null, null);
        var reviewOnly = draft(author, null, "all");
        var policyOnly = draft(author, "public", null);
        var both = draft(author, "followers", "none");

        migrate("59");

        assertThat(draft(leftToDefaults).has("quoteReview")).isFalse();
        assertThat(draft(leftToDefaults).path("quotePolicy").isNull()).isTrue();
        assertThat(draft(leftToDefaults).has("quoteOthers")).isFalse();
        assertThat(quoteFields(reviewOnly)).containsExactly("nobody", "review");
        assertThat(quoteFields(policyOnly)).containsExactly("followers", "review");
        assertThat(quoteFields(both)).containsExactly("followers", "reject");
        assertThat(draft(both).has("quoteReview")).isFalse();
    }

    private UUID defaults(String policy, String review) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".actor (id, uri, username, domain, inbox_uri, is_local) "
                        + "VALUES (:id, :uri, 'remote', 'migration.test', :uri, false)")
                .param("id", id).param("uri", "https://migration.test/users/" + id).update();
        jdbc.sql("INSERT INTO " + SCHEMA + ".posting_default (actor_id, quote_policy, quote_review) "
                        + "VALUES (:id, :policy, :review)")
                .param("id", id).param("policy", policy).param("review", review).update();
        return id;
    }

    private List<String> defaults(UUID actorId) {
        return jdbc.sql("SELECT quote_policy, quote_others FROM " + SCHEMA + ".posting_default WHERE actor_id = :id")
                .param("id", actorId).query((rs, n) -> List.of(rs.getString(1), rs.getString(2))).single();
    }

    private UUID draft(UUID actorId, String policy, String review) {
        var id = UUID.randomUUID();
        var params = json.createObjectNode().put("text", "later").put("quotePolicy", policy).put("quoteReview", review);
        jdbc.sql("INSERT INTO " + SCHEMA + ".scheduled_status (id, actor_id, scheduled_at, params) "
                        + "VALUES (:id, :actorId, now() + interval '1 day', CAST(:params AS jsonb))")
                .param("id", id).param("actorId", actorId).param("params", params.toString()).update();
        return id;
    }

    private JsonNode draft(UUID id) {
        return json.readTree(jdbc.sql("SELECT params::text FROM " + SCHEMA + ".scheduled_status WHERE id = :id")
                .param("id", id).query(String.class).single());
    }

    private List<String> quoteFields(UUID id) {
        var params = draft(id);
        return List.of(params.path("quotePolicy").asString(), params.path("quoteOthers").asString());
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load().migrate();
    }
}
