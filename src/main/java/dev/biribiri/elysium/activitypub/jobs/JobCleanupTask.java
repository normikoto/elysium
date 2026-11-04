package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

/**
 * Removes what jobs leave behind. It's a recurring job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class JobCleanupTask {

    /**
     * Failed history lasts as long as a parked job, so an admin looking at one can still see why it failed. Activities last
     * a little longer than that, so that they're still there if it's retried at the last moment.
     */
    private static final String REMOVE_OLD_ROWS = """
            WITH history AS (
                DELETE FROM scheduled_execution_logs
                WHERE time_finished < now() - CASE WHEN succeeded THEN INTERVAL '7 days' ELSE INTERVAL '30 days' END
            ), outbound AS (
                DELETE FROM outbound_activity WHERE created_at < now() - INTERVAL '33 days'
            ), inbound AS (
                DELETE FROM inbound_activity WHERE received_at < now() - INTERVAL '33 days'
            )
            DELETE FROM federation_host WHERE retry_after < now() - INTERVAL '7 days'
            """;

    @Bean
    Task<Void> jobCleanup(JdbcClient jdbc) {
        return Tasks.recurring("job-cleanup", FixedDelay.of(Duration.ofDays(1)))
                .execute((instance, context) -> jdbc.sql(REMOVE_OLD_ROWS).update());
    }
}
