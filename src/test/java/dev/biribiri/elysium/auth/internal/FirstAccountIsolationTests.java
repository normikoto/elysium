package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing the first account has to work even if connections default to a stricter isolation level. There, a transaction
 * that waited for the lock would otherwise keep counting from a snapshot taken before the other account was committed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://first-account-isolation.example",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
class FirstAccountIsolationTests {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void anAccountThatWaitedForTheFirstIsNotAnAdmin() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var second = new AtomicReference<Future<Optional<AccountRole>>>();
            transaction.executeWithoutResult(status -> {
                assertThat(accounts.createAccount("first", PASSWORD)).contains(AccountRole.ADMIN);
                second.set(executor.submit(() -> accounts.createAccount("second", PASSWORD)));
                awaitSomethingWaitingForALock();
            });

            assertThat(second.get().get(30, TimeUnit.SECONDS)).contains(AccountRole.USER);
        }
    }

    private void awaitSomethingWaitingForALock() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                .query(Long.class)
                .single() == 0) {
            assertThat(System.nanoTime()).as("Nothing waited for the lock").isLessThan(deadline);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
