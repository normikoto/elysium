package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class FeaturedFetchTask {

    private final FeaturedFetch featured;

    /**
     * @param featured lazy, because it needs the scheduler, which needs this task
     */
    FeaturedFetchTask(@Lazy FeaturedFetch featured) {
        this.featured = featured;
    }

    @Bean
    Task<FeaturedFetchJob> featuredCollectionFetch() {
        return Jobs.task(FeaturedFetch.TASK, featured::run);
    }
}
