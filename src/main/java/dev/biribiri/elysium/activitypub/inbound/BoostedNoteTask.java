package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class BoostedNoteTask {

    private final Boosts boosts;

    /**
     * @param boosts lazy, because it needs the scheduler, which needs this task
     */
    BoostedNoteTask(@Lazy Boosts boosts) {
        this.boosts = boosts;
    }

    @Bean
    Task<BoostedNoteJob> boostedNoteFetch() {
        return Jobs.task(Boosts.TASK, boosts::run);
    }
}
