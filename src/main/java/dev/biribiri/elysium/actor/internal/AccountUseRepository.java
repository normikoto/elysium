package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.ActorSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class AccountUseRepository {

    private final JdbcClient jdbc;

    AccountUseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Skips an account deleted since the request was authenticated. Its {@code local_account} row outlives the deletion
     * for a while, so without checking the actor a day could be written after the account's cleanup had removed the
     * others, and be kept until it expired.
     */
    public void insert(UUID actorId, LocalDate day) {
        jdbc.sql("""
                        INSERT INTO account_use_day (actor_id, day)
                        SELECT local_account.actor_id, :day FROM local_account
                        JOIN actor ON actor.id = local_account.actor_id
                        WHERE local_account.actor_id = :actorId AND actor.deleted_at IS NULL
                        ON CONFLICT DO NOTHING
                        """)
                .param("actorId", actorId)
                .param("day", day)
                .update();
    }

    public long countAccountsSince(LocalDate since) {
        return jdbc.sql("""
                        SELECT count(DISTINCT u.actor_id) FROM account_use_day u
                        JOIN actor ON actor.id = u.actor_id
                        WHERE u.day >= :since AND\s""" + ActorSql.active("actor"))
                .param("since", since)
                .query(Long.class)
                .single();
    }

    /**
     * @return the number of accounts that used the server in each week, keyed by the Monday it starts on
     */
    public Map<LocalDate, Long> countAccountsByWeek(LocalDate since) {
        var weeks = new HashMap<LocalDate, Long>();
        // A date on its own is truncated as a timestamptz, in the connection's time zone, rather than as the UTC day it is.
        jdbc.sql("""
                        SELECT date_trunc('week', u.day::timestamp)::date AS week, count(DISTINCT u.actor_id) AS accounts
                        FROM account_use_day u
                        JOIN actor ON actor.id = u.actor_id
                        WHERE u.day >= :since AND %s
                        GROUP BY week
                        """.formatted(ActorSql.active("actor")))
                .param("since", since)
                .query(rs -> {
                    weeks.put(rs.getObject("week", LocalDate.class), rs.getLong("accounts"));
                });
        return weeks;
    }

    public void deleteBefore(LocalDate day) {
        jdbc.sql("DELETE FROM account_use_day WHERE day < :day").param("day", day).update();
    }

    public void deleteForActor(UUID actorId) {
        jdbc.sql("DELETE FROM account_use_day WHERE actor_id = :actorId").param("actorId", actorId).update();
    }
}
