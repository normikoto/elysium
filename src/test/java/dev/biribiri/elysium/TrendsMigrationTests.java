package dev.biribiri.elysium;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TrendsMigrationTests {

    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void indexesRecentExistingPostsAcrossBackfillBatches() {
        var schema = "trends_migration";
        try {
            migrate(schema, "80");
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> seedPosts());
            migrate(schema, "82");

            assertThat(jdbc.sql("SELECT count(*) FROM trends_migration.status_link").query(Long.class).single())
                    .isEqualTo(501);
            assertThat(jdbc.sql("SELECT DISTINCT url FROM trends_migration.status_link").query(String.class).list())
                    .containsExactly("https://news.example/a");
        } finally {
            jdbc.sql("DROP SCHEMA IF EXISTS trends_migration CASCADE").update();
        }
    }

    private void seedPosts() {
        // Status counts are maintained by a trigger that reads unqualified table names.
        jdbc.sql("SET LOCAL search_path TO trends_migration").update();
        var author = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri, is_local)
                        VALUES (:id, 'https://trends.test/users/a', 'a', 'trends.test', 'https://trends.test/inbox', false)
                        """).param("id", author).update();
        jdbc.sql("""
                        INSERT INTO status (id, uri, actor_id, text, content, visibility, published_at, local_only)
                        SELECT gen_random_uuid(), 'https://trends.test/posts/' || n, :author, 'Read',
                               '<p><a href="https://NEWS.example:443/a#section">Read</a></p>',
                               CASE WHEN n = 502 THEN 'unlisted' ELSE 'public' END,
                               CASE WHEN n = 503 THEN now() - interval '8 days' ELSE now() END,
                               n = 504
                        FROM generate_series(1, 504) AS n
                        """).param("author", author).update();
    }

    private void migrate(String schema, String target) {
        var configuration = flyway.getConfiguration();
        Flyway.configure().dataSource(configuration.getDataSource()).schemas(schema)
                .javaMigrations(configuration.getJavaMigrations()).target(target).load().migrate();
    }
}
