package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class ForwardedActivityTask {

    private final ForwardedActivities forwarded;

    /**
     * @param forwarded lazy, because it needs the scheduler, which needs this task
     */
    ForwardedActivityTask(@Lazy ForwardedActivities forwarded) {
        this.forwarded = forwarded;
    }

    @Bean
    Task<ForwardedJob> forwardedActivity() {
        return Jobs.task(ForwardedActivities.TASK, job -> {
            forwarded.fetch(job);
            return Jobs.done();
        });
    }
}
