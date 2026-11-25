package dev.biribiri.elysium.jobs;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.jobs.JobBacklog.State;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the queue directly, because a failed read only logs a warning, and the gauges would still report zero.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://elysium.example", "elysium.jobs.worker-enabled=false"})
class JobBacklogIntegrationTests {

    private static final String TASK = "backlog-test";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JobBacklog backlog;

    @Test
    void countsJobsByState() {
        job("running", "now() - interval '1 minute'", true, null, null);
        job("due", "now() - interval '10 minutes'", false, 0, null);
        job("new-later", "now() + interval '1 hour'", false, null, null);
        job("waiting", "now() + interval '1 hour'", false, 0, null);
        job("retrying", "now() + interval '1 hour'", false, 2, "now()");
        job("parked", "now() + interval '30 days'", false, 5, "now()");

        var counts = backlog.read().get(TASK);

        assertThat(counts.byState()).isEqualTo(Map.of(
                State.RUNNING, 1L, State.DUE, 1L, State.SCHEDULED, 2L, State.RETRYING, 1L, State.PARKED, 1L));
        assertThat(counts.oldestDue().toMinutes()).isEqualTo(10);
    }

    private void job(String instance, String executionTime, boolean picked, Integer failures, String lastFailure) {
        jdbc.sql("""
                        INSERT INTO scheduled_tasks
                            (task_name, task_instance, execution_time, picked, consecutive_failures, last_failure, version)
                        VALUES (:task, :instance, %s, :picked, :failures, %s, 1)
                        """.formatted(executionTime, lastFailure == null ? "NULL" : lastFailure))
                .param("task", TASK)
                .param("instance", instance)
                .param("picked", picked)
                .param("failures", failures, java.sql.Types.INTEGER)
                .update();
    }
}
