package dev.biribiri.elysium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules that other modules' SQL has to agree with are written once, in {@code actor.ActorSql} and the like, since a copy
 * that drifts shows something it shouldn't, such as what's from a suspended server. The module boundaries can't see this,
 * because SQL is only text, so this reads the sources.
 */
class SharedSqlRulesTests {

    private static final Path SOURCES = Path.of("src/main/java/dev/biribiri/elysium");
    private static final Path ACTOR_SQL = SOURCES.resolve("actor/ActorSql.java");

    /**
     * {@code account_domain_block} is a different table, which {@code graph} owns.
     */
    private static final Pattern READS_DOMAIN_BLOCKS = Pattern.compile("(?<!account_)\\bdomain_block\\b(?!_)");

    /**
     * How a block's domain is matched to an actor's, which ignores the actor's port.
     */
    private static final Pattern MATCHES_DOMAINS = Pattern.compile(Pattern.quote("':[0-9]+$'"));

    /**
     * Half of {@code actor.ActorSql.active}, which is enough to find a copy of it.
     */
    private static final Pattern CHECKS_ACTIVE = Pattern.compile("\\bsuspended_at IS NULL");

    @Test
    void onlyModerationAndActorSqlReadDomainBlocks() throws IOException {
        var moderation = SOURCES.resolve("moderation");
        assertThat(sourcesMatching(READS_DOMAIN_BLOCKS, path -> !path.startsWith(moderation) && !path.equals(ACTOR_SQL)))
                .as("SQL outside moderation should read domain_block through actor.ActorSql")
                .isEmpty();
    }

    @Test
    void onlyActorSqlMatchesDomains() throws IOException {
        assertThat(sourcesMatching(MATCHES_DOMAINS, path -> !path.equals(ACTOR_SQL)))
                .as("match a block's domain with actor.ActorSql.domainCovers")
                .isEmpty();
    }

    @Test
    void onlyActorChecksWhetherAnActorIsActive() throws IOException {
        var actor = SOURCES.resolve("actor");
        assertThat(sourcesMatching(CHECKS_ACTIVE, path -> !path.startsWith(actor)))
                .as("SQL outside actor should check that an actor is active with actor.ActorSql.active")
                .isEmpty();
    }

    @Test
    void theRulesTellTheTablesApart() {
        assertThat(READS_DOMAIN_BLOCKS.matcher("SELECT 1 FROM domain_block b").find()).isTrue();
        assertThat(READS_DOMAIN_BLOCKS.matcher("SELECT 1 FROM account_domain_block hd").find()).isFalse();
        assertThat(READS_DOMAIN_BLOCKS.matcher("\"existing_domain_block\"").find()).isFalse();
    }

    private static List<Path> sourcesMatching(Pattern pattern, Predicate<Path> checked) throws IOException {
        try (Stream<Path> paths = Files.walk(SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(checked)
                    .filter(path -> pattern.matcher(read(path)).find())
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
