package dev.biribiri.elysium.graph.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.graph.MuteService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Removes expired mutes, only to save space, since every query already ignores them. It's a recurring job, so only one
 * node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class MuteExpiryTask {

    @Bean
    Task<Void> muteExpiry(MuteService mutes) {
        return Tasks.recurring("mute-expiry", FixedDelay.of(Duration.ofDays(1)))
                .execute((instance, context) -> mutes.removeExpired());
    }
}
