package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.serializer.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import org.springframework.beans.factory.annotation.Value;
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
     * own pool otherwise, whatever {@code spring.threads.virtual.enabled} says. They're still in a fixed-size pool, because
     * that's the only thing limiting how many jobs run at once: the library fetches several times this many, and hands them
     * all to the executor.
     *
     * <p>This is the deprecated way to set these, rather than {@code DbSchedulerOverrides}, because db-scheduler-ui 5.0.0
     * reads the serializer from it to show job data, and fails to start without one. Move to the overrides once the UI
     * supports them.
     */
    @Bean
    @SuppressWarnings("removal")
    DbSchedulerCustomizer jobSchedulerCustomizer(@Value("${db-scheduler.threads}") int threads) {
        return new DbSchedulerCustomizer() {
            @Override
            public Optional<Serializer> serializer() {
                return Optional.of(new Jackson3Serializer());
            }

            @Override
            public Optional<ExecutorService> executorService() {
                return Optional.of(Executors.newFixedThreadPool(threads, Thread.ofVirtual().factory()));
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
