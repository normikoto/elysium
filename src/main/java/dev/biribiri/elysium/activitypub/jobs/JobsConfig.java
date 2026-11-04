package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.boot.autoconfigure.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
class JobsConfig {

    /**
     * Job data is stored as JSON so that admins can read it. Java serialization, the library's fallback, couldn't be read,
     * and would make job data break whenever a class changes.
     */
    @Bean
    DbSchedulerCustomizer jobDataAsJson() {
        return new DbSchedulerCustomizer() {
            @Override
            public Optional<Serializer> serializer() {
                return Optional.of(new Jackson3Serializer());
            }
        };
    }

    /**
     * Commands start the whole application, sometimes alongside the server, and a command must not run any jobs. It would
     * claim them and then exit in the middle of them. Replacing this bean keeps the scheduler's other beans available.
     */
    @Bean
    @ConditionalOnProperty(name = "elysium.jobs.worker-enabled", havingValue = "false")
    DbSchedulerStarter noJobWorker() {
        return () -> {
        };
    }
}
