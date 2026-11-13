package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class PollResultsTask {

    private final PollDeliveries deliveries;

    /**
     * @param deliveries lazy, because it needs the scheduler, which needs this task
     */
    PollResultsTask(@Lazy PollDeliveries deliveries) {
        this.deliveries = deliveries;
    }

    @Bean
    Task<PollResultsJob> pollResults() {
        return Jobs.task(PollDeliveries.RESULTS_TASK, deliveries::sendResults);
    }
}
