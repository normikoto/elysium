package dev.biribiri.elysium.auth.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Removes apps that nobody authorized. Registering one needs no login, and the limit on registrations only slows down how
 * fast they build up. It's a recurring job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class UnauthorizedClientCleanupTask {

    static final String NAME = "unauthorized-client-cleanup";

    @Bean
    Task<Void> unauthorizedClientCleanup(AuthorizedClients clients) {
        return Tasks.recurring(NAME, FixedDelay.of(Duration.ofHours(1)))
                .execute((instance, context) -> clients.removeUnauthorized());
    }
}
