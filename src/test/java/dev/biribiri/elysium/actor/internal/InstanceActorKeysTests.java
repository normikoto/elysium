package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each test uses new objects, as separate instances of this server would, because the key is only read once per object.
 * The base URL gives this class its own context, and so its own database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://instance-keys.example")
class InstanceActorKeysTests {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM instance_actor").update();
    }

    @Test
    void createsAKeyThatLastsAcrossRestarts() {
        var first = newKeys().get();

        var second = newKeys().get();

        assertThat(second).isEqualTo(first);
        assertThat(RsaKeys.parsePrivateKey(first.privateKeyPem())).isNotNull();
    }

    @Test
    void remembersTheKeyOnceItHasOne() {
        var keys = newKeys();
        var first = keys.get();

        jdbc.sql("DELETE FROM instance_actor").update();

        assertThat(keys.get()).isSameAs(first);
    }

    @Test
    void instancesStartingTogetherAgreeOnOneKey() throws Exception {
        var count = 4;
        var ready = new CountDownLatch(count);
        var tasks = new ArrayList<Callable<RsaKeys>>();
        for (var i = 0; i < count; i++) {
            var keys = newKeys();
            tasks.add(() -> {
                ready.countDown();
                ready.await();
                return keys.get();
            });
        }

        try (var executor = Executors.newFixedThreadPool(count)) {
            var results = new ArrayList<RsaKeys>();
            for (var future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
            assertThat(results).hasSize(count).containsOnly(results.getFirst());
        }
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void keepsAKeyCreatedInATransactionThatRollsBack() {
        var keys = newKeys();
        var transaction = new TransactionTemplate(transactionManager);

        var created = transaction.execute(status -> {
            status.setRollbackOnly();
            return keys.get();
        });

        assertThat(rows()).isEqualTo(1);
        assertThat(newKeys().get()).isEqualTo(created);
    }

    /**
     * The caller's view of the database is from before the other instance's key, so it wouldn't be able to find it.
     */
    @Test
    void findsAKeyThatACallersOlderViewOfTheDatabaseCannotSee() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        var found = transaction.execute(status -> {
            assertThat(rows()).isZero();
            var other = newKeys().get();
            assertThat(rows()).isZero();
            assertThat(newKeys().get()).isEqualTo(other);
            return other;
        });

        assertThat(rows()).isEqualTo(1);
        assertThat(found).isNotNull();
    }

    @Test
    void theTableOnlyAllowsOneRow() {
        var keys = newKeys().get();

        var inserted = jdbc.sql("""
                        INSERT INTO instance_actor (public_key_pem, private_key_pem) VALUES (:pub, :priv)
                        ON CONFLICT DO NOTHING
                        """)
                .param("pub", keys.publicKeyPem())
                .param("priv", keys.privateKeyPem())
                .update();

        assertThat(inserted).isZero();
    }

    private InstanceActorKeys newKeys() {
        return new InstanceActorKeys(jdbc, transactionManager);
    }

    private long rows() {
        return jdbc.sql("SELECT count(*) FROM instance_actor").query(Long.class).single();
    }
}
