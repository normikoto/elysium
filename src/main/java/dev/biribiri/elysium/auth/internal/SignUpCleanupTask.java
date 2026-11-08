package dev.biribiri.elysium.auth.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Removes the email addresses and password hashes of sign-ups whose links have expired. Account creation already frees
 * their usernames and addresses, but a server that stops taking sign-ups would otherwise keep them forever. It's a recurring
 * job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class SignUpCleanupTask {

    static final String NAME = "sign-up-cleanup";

    @Bean
    Task<Void> signUpCleanup(LocalAccounts accounts) {
        return Tasks.recurring(NAME, FixedDelay.of(Duration.ofHours(1)))
                .execute((instance, context) -> accounts.removeExpiredSignUps());
    }
}
