package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class PollRefreshTask {

    private final PollRefreshes refreshes;

    /**
     * @param refreshes lazy, because it needs the scheduler, which needs this task
     */
    PollRefreshTask(@Lazy PollRefreshes refreshes) {
        this.refreshes = refreshes;
    }

    @Bean
    Task<PollRefreshJob> pollRefresh() {
        return Jobs.task(PollRefreshes.TASK, refreshes::run);
    }
}
