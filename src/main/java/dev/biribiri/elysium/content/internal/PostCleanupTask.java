package dev.biribiri.elysium.content.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.content.PostCleanups;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Deletes old statuses for the accounts that asked for it, a few at a time, as {@link PostCleanups} explains. It's a
 * recurring job, so only one node runs it at a time, and a status deleted meanwhile is only skipped.
 */
@Configuration(proxyBeanMethods = false)
class PostCleanupTask {

    @Bean
    Task<Void> postCleanup(PostCleanups cleanups) {
        return Tasks.recurring("post-cleanup", FixedDelay.of(Duration.ofMinutes(10)))
                .execute((instance, context) -> cleanups.runDue());
    }
}
