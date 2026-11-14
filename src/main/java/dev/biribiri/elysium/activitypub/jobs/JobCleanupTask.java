package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.MuteService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Removes what jobs leave behind, and expired mutes, which every query already ignores. It's a recurring job, so only one
 * node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class JobCleanupTask {

    private static final Duration SUCCESSFUL_HISTORY_KEPT = Duration.ofDays(7);
    private static final Duration HOSTS_KEPT = Duration.ofDays(7);

    /**
     * Failed history lasts as long as a parked job, so an admin looking at one can still see why it failed.
     */
    private static final Duration FAILED_HISTORY_KEPT = RetryPolicy.PARKED_FOR;

    @Bean
    Task<Void> jobCleanup(JdbcClient jdbc, ActorService actors, MuteService mutes) {
        return Tasks.recurring("job-cleanup", FixedDelay.of(Duration.ofDays(1)))
                .execute((instance, context) -> {
                    var now = Instant.now();
                    removeOldRows(jdbc, now);
                    // Only once their last activities can't be sent any more, including by an admin retrying one.
                    actors.removeKeysOfDeleted(now.minus(Jobs.ACTIVITIES_KEPT));
                    mutes.removeExpired();
                });
    }

    /**
     * These are separate statements so that each one's locks are held only as long as it needs them. It's safe to stop
     * partway, because the next run removes whatever is left.
     */
    private static void removeOldRows(JdbcClient jdbc, Instant now) {
        jdbc.sql("""
                        DELETE FROM scheduled_execution_logs
                        WHERE time_started < :successfulBefore AND (succeeded OR time_started < :failedBefore)
                        """)
                .param("successfulBefore", before(now, SUCCESSFUL_HISTORY_KEPT))
                .param("failedBefore", before(now, FAILED_HISTORY_KEPT))
                .update();
        jdbc.sql("DELETE FROM outbound_activity WHERE created_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM inbound_activity WHERE received_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM undone_follow WHERE undone_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM undone_follow_pair WHERE undone_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM undone_block WHERE undone_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM undone_block_pair WHERE undone_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM undone_like WHERE undone_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM undone_like_pair WHERE undone_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM removed_pin WHERE removed_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM deleted_remote_status WHERE deleted_at < :before")
                .param("before", before(now, Jobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM federation_host WHERE retry_after < :before")
                .param("before", before(now, HOSTS_KEPT))
                .update();
    }

    private static OffsetDateTime before(Instant now, Duration age) {
        return now.minus(age).atOffset(ZoneOffset.UTC);
    }
}
