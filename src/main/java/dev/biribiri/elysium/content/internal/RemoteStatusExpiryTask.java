package dev.biribiri.elysium.content.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.content.RemoteStatusExpiry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Removes other servers' old statuses, as {@link RemoteStatusExpiry} explains, and does nothing unless that's turned on.
 * It's a recurring job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class RemoteStatusExpiryTask {

    @Bean
    Task<Void> remoteStatusExpiryJob(RemoteStatusExpiry expiry) {
        return Tasks.recurring("remote-status-expiry", FixedDelay.of(Duration.ofMinutes(10)))
                .execute((instance, context) -> expiry.runDue());
    }
}
