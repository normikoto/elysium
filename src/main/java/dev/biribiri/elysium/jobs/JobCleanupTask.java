package dev.biribiri.elysium.jobs;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Removes old job history. It's a recurring job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class JobCleanupTask {

    private static final Duration SUCCESSFUL_HISTORY_KEPT = Duration.ofDays(7);

    /**
     * Failed history lasts as long as a parked job, so an admin looking at one can still see why it failed.
     */
    private static final Duration FAILED_HISTORY_KEPT = RetryPolicy.PARKED_FOR;

    @Bean
    Task<Void> jobCleanup(JdbcClient jdbc) {
        return Tasks.recurring("job-cleanup", FixedDelay.of(Duration.ofDays(1)))
                .execute((instance, context) -> {
                    var now = Instant.now();
                    jdbc.sql("""
                                    DELETE FROM scheduled_execution_logs
                                    WHERE time_started < :successfulBefore AND (succeeded OR time_started < :failedBefore)
                                    """)
                            .param("successfulBefore", now.minus(SUCCESSFUL_HISTORY_KEPT).atOffset(ZoneOffset.UTC))
                            .param("failedBefore", now.minus(FAILED_HISTORY_KEPT).atOffset(ZoneOffset.UTC))
                            .update();
                });
    }
}
