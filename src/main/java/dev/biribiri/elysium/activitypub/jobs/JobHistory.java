package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import no.bekk.dbscheduler.ui.log.LogRepository;
import no.bekk.dbscheduler.ui.log.listener.LogSchedulerListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces db-scheduler-ui's recorder of job history, which it only makes if there isn't one, to leave out runs that only
 * waited. Every run would otherwise be a row, and a backlog for a server that answers slowly can wait about 3 times a second,
 * which is around 259,000 rows a day, each kept for a week.
 */
@Configuration(proxyBeanMethods = false)
class JobHistory {

    @Bean
    @ConditionalOnProperty(name = "db-scheduler-ui.log.enabled", havingValue = "true")
    LogSchedulerListener jobHistoryListener(LogRepository repository) {
        return new LogSchedulerListener(repository) {
            @Override
            public void onExecutionComplete(ExecutionComplete complete) {
                if (!Jobs.onlyWaited(complete)) {
                    super.onExecutionComplete(complete);
                }
            }
        };
    }
}
