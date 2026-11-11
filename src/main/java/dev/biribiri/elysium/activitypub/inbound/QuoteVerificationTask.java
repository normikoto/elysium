package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class QuoteVerificationTask {

    private final QuoteVerifications verifications;

    /**
     * @param verifications lazy, because it needs the scheduler, which needs this task
     */
    QuoteVerificationTask(@Lazy QuoteVerifications verifications) {
        this.verifications = verifications;
    }

    @Bean
    Task<QuoteVerificationJob> quoteVerification() {
        return Jobs.task(QuoteVerifications.TASK, verifications::run);
    }
}
