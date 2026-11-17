package dev.biribiri.elysium.content.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.content.ScheduledStatusService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Posts scheduled statuses once they're due, so they're posted within about a minute of their time. It's a recurring
 * job, so only one node runs it at a time, and a status is only posted once even if two runs overlap.
 */
@Configuration(proxyBeanMethods = false)
class ScheduledStatusTask {

    static final String NAME = "scheduled-status-publish";

    @Bean
    Task<Void> scheduledStatusPublish(ScheduledStatusService scheduled) {
        return Tasks.recurring(NAME, FixedDelay.of(Duration.ofMinutes(1)))
                .execute((instance, context) -> scheduled.publishDue());
    }
}
