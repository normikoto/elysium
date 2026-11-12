package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class QuoteRequestResendTask {

    private final QuoteDeliveries deliveries;

    /**
     * @param deliveries lazy, because it needs the scheduler, which needs this task
     */
    QuoteRequestResendTask(@Lazy QuoteDeliveries deliveries) {
        this.deliveries = deliveries;
    }

    @Bean
    Task<QuoteRequestResendJob> quoteRequestResend() {
        return Jobs.task(QuoteDeliveries.RESEND_TASK, deliveries::resend);
    }
}
