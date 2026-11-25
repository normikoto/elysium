package dev.biribiri.elysium.jobs;

import com.github.kagkarlsson.scheduler.task.Task;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Reports how many jobs are queued for each task, and in what state, as metrics. db-scheduler's own metrics count runs,
 * and a run that only waited for a busy server counts as a success, so they can't show a backlog building up.
 *
 * <p>Every node reads the same table, so each reports the whole queue, not its own share. The counts are read at most once
 * every {@link #REFRESH}, because they need a scan of the queue, which a large fan-out makes long, and scrapes could
 * otherwise come from several Prometheus servers.
 */
@Component
class JobBacklog {

    private static final Logger log = LoggerFactory.getLogger(JobBacklog.class);
    private static final Duration REFRESH = Duration.ofSeconds(30);

    /**
     * A retry is never more than about 7 hours after its failure, and parking is 30 days, so anything this far out is
     * parked.
     */
    private static final Duration PARKED_AFTER = RetryPolicy.PARKED_FOR.dividedBy(2);

    enum State {
        /** A worker has it. */
        RUNNING,
        /** Its time has come and it's waiting for a worker, which is what a backlog looks like. */
        DUE,
        /** It's set to run later without having failed, including waits for a busy server. */
        SCHEDULED,
        /** It failed and will be tried again. */
        RETRYING,
        /** It was given up on, and stays for an admin to look at. */
        PARKED
    }

    record Counts(Map<State, Long> byState, Duration oldestDue) {
        static final Counts NONE = new Counts(Map.of(), Duration.ZERO);
    }

    private final JdbcClient jdbc;
    private final ReentrantLock reading = new ReentrantLock();
    private volatile Map<String, Counts> counts = Map.of();
    private volatile Instant readAt = Instant.MIN;

    JobBacklog(JdbcClient jdbc, MeterRegistry meters, List<Task<?>> tasks) {
        this.jdbc = jdbc;
        // Registered for every task up front, so that a task with nothing queued reports zero rather than nothing.
        for (var task : tasks.stream().map(Task::getName).distinct().toList()) {
            for (var state : State.values()) {
                Gauge.builder("elysium.jobs.queued", this, backlog -> backlog.count(task, state))
                        .description("Jobs in the queue")
                        .tag("task", task)
                        .tag("state", state.name().toLowerCase(Locale.ROOT))
                        .register(meters);
            }
            TimeGauge.builder("elysium.jobs.oldest.due", this, TimeUnit.SECONDS, backlog -> backlog.oldestDue(task))
                    .description("How long the longest-waiting due job has been due")
                    .tag("task", task)
                    .register(meters);
        }
    }

    private double count(String task, State state) {
        return current().getOrDefault(task, Counts.NONE).byState().getOrDefault(state, 0L);
    }

    private double oldestDue(String task) {
        return current().getOrDefault(task, Counts.NONE).oldestDue().toMillis() / 1000.0;
    }

    /**
     * A failed read keeps the last counts, so that a database outage shows as stale numbers and not as an empty queue.
     * While one read waits, such as for a connection from a full pool, other gauges and scrapes take the last counts
     * instead of queueing behind it, so that the rest of the metrics still arrive in time.
     */
    private Map<String, Counts> current() {
        if (readAt.plus(REFRESH).isAfter(Instant.now()) || !reading.tryLock()) {
            return counts;
        }
        try {
            var now = Instant.now();
            if (readAt.plus(REFRESH).isAfter(now)) {
                return counts;
            }
            readAt = now;
            counts = read();
        } catch (DataAccessException e) {
            log.warn("Couldn't count queued jobs for metrics", e);
        } finally {
            reading.unlock();
        }
        return counts;
    }

    Map<String, Counts> read() {
        return jdbc.sql("""
                        SELECT task_name, state, count(*) AS jobs, max(due_for) AS oldest_due
                        FROM (SELECT task_name,
                                     CASE WHEN picked THEN 'RUNNING'
                                          WHEN execution_time <= now() THEN 'DUE'
                                          -- db-scheduler leaves it null on a new job, until its first run.
                                          WHEN coalesce(consecutive_failures, 0) = 0 THEN 'SCHEDULED'
                                          WHEN last_failure IS NOT NULL
                                               AND execution_time >= last_failure + make_interval(secs => :parkedAfter)
                                              THEN 'PARKED'
                                          ELSE 'RETRYING'
                                     END AS state,
                                     CASE WHEN NOT picked AND execution_time <= now()
                                          THEN extract(epoch FROM now() - execution_time)
                                     END AS due_for
                              FROM scheduled_tasks) job
                        GROUP BY task_name, state
                        """)
                .param("parkedAfter", PARKED_AFTER.toSeconds())
                .query((rs, row) -> new Row(
                        rs.getString("task_name"),
                        State.valueOf(rs.getString("state")),
                        rs.getLong("jobs"),
                        rs.getDouble("oldest_due")))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Row::task, Collectors.collectingAndThen(Collectors.toList(), JobBacklog::counts)));
    }

    private record Row(String task, State state, long jobs, double oldestDueSeconds) {
    }

    private static Counts counts(List<Row> rows) {
        var byState = rows.stream().collect(Collectors.toMap(Row::state, Row::jobs));
        var oldestDue = rows.stream()
                .filter(row -> row.state() == State.DUE)
                .findFirst()
                .map(row -> Duration.ofMillis(Math.round(row.oldestDueSeconds() * 1000)))
                .orElse(Duration.ZERO);
        return new Counts(byState, oldestDue);
    }
}
