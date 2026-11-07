package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class DeletionCheckTask {

    private final DeletionChecks checks;

    /**
     * @param checks lazy, because it needs the scheduler, which needs this task
     */
    DeletionCheckTask(@Lazy DeletionChecks checks) {
        this.checks = checks;
    }

    @Bean
    Task<DeletionCheckJob> deletionCheck() {
        return Jobs.task(DeletionChecks.TASK, checks::run);
    }
}
