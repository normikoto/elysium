package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class ThreadFetchTask {

    private final ThreadFetches fetches;

    /**
     * @param fetches lazy, because it needs the scheduler, which needs this task
     */
    ThreadFetchTask(@Lazy ThreadFetches fetches) {
        this.fetches = fetches;
    }

    @Bean
    Task<ThreadFetchJob> threadFetch() {
        return Jobs.task(ThreadFetches.TASK, fetches::run);
    }
}
