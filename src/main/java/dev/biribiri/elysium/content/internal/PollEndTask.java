package dev.biribiri.elysium.content.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.content.PollService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Ends polls whose voting has closed, so that their voters are told and other servers are sent their results. Whether a
 * poll has ended is worked out from its times wherever it's shown, so a late run only delays those. It's a recurring job,
 * so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class PollEndTask {

    static final String NAME = "poll-end";

    @Bean
    Task<Void> pollEnd(PollService polls) {
        return Tasks.recurring(NAME, FixedDelay.of(Duration.ofMinutes(1)))
                .execute((instance, context) -> polls.endDue());
    }
}
