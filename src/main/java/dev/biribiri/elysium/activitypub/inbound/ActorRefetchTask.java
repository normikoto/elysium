package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class ActorRefetchTask {

    private final ActorRefetch refetch;

    /**
     * @param refetch lazy, because it needs the scheduler, which needs this task
     */
    ActorRefetchTask(@Lazy ActorRefetch refetch) {
        this.refetch = refetch;
    }

    @Bean
    Task<ActorRefetchJob> actorDocumentRefetch() {
        return Jobs.task(ActorRefetch.TASK, refetch::run);
    }
}
