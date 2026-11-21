package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.actor.ActorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Removes the activities and other records that federation's jobs leave behind, once no job can need them. It's a
 * recurring job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class ActivityCleanupTask {

    private static final Duration HOSTS_KEPT = Duration.ofDays(7);

    @Bean
    Task<Void> activityCleanup(JdbcClient jdbc, ActorService actors) {
        return Tasks.recurring("activity-cleanup", FixedDelay.of(Duration.ofDays(1)))
                .execute((instance, context) -> {
                    var now = Instant.now();
                    removeOldRows(jdbc, now);
                    // Only once their last activities can't be sent any more, including by an admin retrying one.
                    actors.removeKeysOfDeleted(now.minus(ActivityJobs.ACTIVITIES_KEPT));
                });
    }

    /**
     * These are separate statements so that each one's locks are held only as long as it needs them. It's safe to stop
     * partway, because the next run removes whatever is left.
     */
    private static void removeOldRows(JdbcClient jdbc, Instant now) {
        jdbc.sql("DELETE FROM outbound_activity WHERE created_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM inbound_activity WHERE received_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT))
                .update();
        // Long after a thread can be fetched again, which is all a row is for.
        jdbc.sql("DELETE FROM thread_refresh WHERE started_at < :before")
                .param("before", before(now, Duration.ofDays(1)))
                .update();
        jdbc.sql("DELETE FROM undone_follow WHERE undone_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM undone_follow_pair WHERE undone_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM undone_block WHERE undone_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM undone_block_pair WHERE undone_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM undone_like WHERE undone_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM undone_like_pair WHERE undone_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM undone_reaction WHERE undone_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM removed_pin WHERE removed_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT)).update();
        jdbc.sql("DELETE FROM deleted_remote_status WHERE deleted_at < :before")
                .param("before", before(now, ActivityJobs.ACTIVITIES_KEPT))
                .update();
        jdbc.sql("DELETE FROM federation_host WHERE retry_after < :before")
                .param("before", before(now, HOSTS_KEPT))
                .update();
    }

    private static OffsetDateTime before(Instant now, Duration age) {
        return now.minus(age).atOffset(ZoneOffset.UTC);
    }
}
