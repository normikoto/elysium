package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class OutboxBackfillTask {

    private final OutboxBackfill backfill;

    /**
     * @param backfill lazy, because it needs the scheduler, which needs this task
     */
    OutboxBackfillTask(@Lazy OutboxBackfill backfill) {
        this.backfill = backfill;
    }

    @Bean
    Task<OutboxBackfillJob> outboxBackfillFetch() {
        return Jobs.task(OutboxBackfill.TASK, backfill::run);
    }
}
