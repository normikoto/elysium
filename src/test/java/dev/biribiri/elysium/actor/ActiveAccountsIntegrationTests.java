package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.jobs.worker-enabled=false"
})
class ActiveAccountsIntegrationTests {

    @Autowired
    private ActiveAccounts activeAccounts;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void removesDaysNoCountReaches() {
        var id = newLocal();
        jdbc.sql("""
                        INSERT INTO account_use_day (actor_id, day)
                        VALUES (:id, current_date - :kept), (:id, current_date - :kept - 1)
                        """)
                .param("id", id).param("kept", ActiveAccounts.DAYS_KEPT).update();

        activeAccounts.removeExpired();

        assertThat(jdbc.sql("SELECT day FROM account_use_day WHERE actor_id = :id").param("id", id)
                .query(LocalDate.class).list())
                .containsExactly(LocalDate.now(ZoneOffset.UTC).minusDays(ActiveAccounts.DAYS_KEPT));
    }

    @Test
    void countsEachAccountOnceAWeekFromMonday() {
        var id = newLocal();
        // 2026-11-02 is a Monday.
        jdbc.sql("""
                        INSERT INTO account_use_day (actor_id, day)
                        VALUES (:id, DATE '2026-11-01'), (:id, DATE '2026-11-02'), (:id, DATE '2026-11-08')
                        """)
                .param("id", id).update();

        var weeks = activeAccounts.countByWeek(LocalDate.of(2026, 10, 1));

        assertThat(weeks).containsEntry(LocalDate.of(2026, 10, 26), 1L).containsEntry(LocalDate.of(2026, 11, 2), 1L);
    }

    @Test
    void ignoresAnAccountThatIsNotLocal() {
        var id = UUID.randomUUID();

        activeAccounts.recordUse(id);

        assertThat(jdbc.sql("SELECT count(*) FROM account_use_day WHERE actor_id = :id").param("id", id)
                .query(Long.class).single()).isZero();
    }

    private UUID newLocal() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, actor -> {
        }).orElseThrow().id();
    }
}
