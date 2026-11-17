package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class MoveCheckTask {

    private final MoveChecks checks;

    /**
     * @param checks lazy, because it needs the scheduler, which needs this task
     */
    MoveCheckTask(@Lazy MoveChecks checks) {
        this.checks = checks;
    }

    @Bean
    Task<MoveCheckJob> moveCheck() {
        return Jobs.task(MoveChecks.TASK, checks::run);
    }
}
