package dev.biribiri.elysium;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A query's stream holds its connection until it's closed. Inside a transaction, that doesn't show, because the transaction
 * holds the connection anyway. Outside one, each call that doesn't close it loses a connection, until there are none left.
 */
class DatabaseStreamsTests {

    private static final ArchRule NO_STREAMS_FROM_QUERIES = noClasses()
            .should().callMethod(JdbcClient.MappedQuerySpec.class, "stream")
            .because("its stream holds a connection until it's closed, so read a list instead");

    @Test
    void queriesAreNotReadAsStreams() {
        NO_STREAMS_FROM_QUERIES.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackagesOf(ElysiumApplication.class));
    }

    @Test
    void theRuleFindsAStreamFromAQuery() {
        var classes = new ClassFileImporter().importClasses(ReadsAStream.class);

        assertThatThrownBy(() -> NO_STREAMS_FROM_QUERIES.check(classes)).isInstanceOf(AssertionError.class);
    }

    @Test
    void theRuleAllowsAStreamFromAList() {
        var classes = new ClassFileImporter().importClasses(ReadsAList.class);

        assertThatCode(() -> NO_STREAMS_FROM_QUERIES.check(classes)).doesNotThrowAnyException();
    }

    private static final class ReadsAStream {
        long count(JdbcClient jdbc) {
            return jdbc.sql("SELECT 1").query(Long.class).stream().count();
        }
    }

    private static final class ReadsAList {
        List<Long> read(JdbcClient jdbc) {
            return jdbc.sql("SELECT 1").query(Long.class).list().stream().toList();
        }
    }
}
