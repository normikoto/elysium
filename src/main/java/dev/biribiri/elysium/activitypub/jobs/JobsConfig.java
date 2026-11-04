package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.boot.autoconfigure.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
class JobsConfig {

    /**
     * Job data is stored as JSON so that admins can read it. Java serialization, the library's fallback, couldn't be read,
     * and would make job data break whenever a class changes.
     *
     * <p>Workers are virtual threads, because they spend most of their time waiting on other servers. The library makes its
     * own pool otherwise, whatever {@code spring.threads.virtual.enabled} says.
     */
    @Bean
    DbSchedulerCustomizer jobSchedulerCustomizer() {
        return new DbSchedulerCustomizer() {
            @Override
            public Optional<Serializer> serializer() {
                return Optional.of(new Jackson3Serializer());
            }

            @Override
            public Optional<ExecutorService> executorService() {
                return Optional.of(Executors.newVirtualThreadPerTaskExecutor());
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
