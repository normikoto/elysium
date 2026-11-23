package dev.biribiri.elysium.actor;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Removes the days of use that no count reaches any more, so that what's kept about when accounts are around doesn't
 * grow. It's a recurring job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class AccountUseCleanupTask {

    @Bean
    Task<Void> accountUseCleanup(ActiveAccounts accounts) {
        return Tasks.recurring("account-use-cleanup", FixedDelay.of(Duration.ofDays(1)))
                .execute((instance, context) -> accounts.removeExpired());
    }
}
