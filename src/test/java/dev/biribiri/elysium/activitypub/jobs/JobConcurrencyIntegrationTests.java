package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The library fetches several times as many jobs as it has threads and hands them all to the executor, so the executor is
 * what limits how many run at once.
 */
@Import({TestcontainersConfiguration.class, JobConcurrencyIntegrationTests.Config.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.threads=3"})
class JobConcurrencyIntegrationTests {

    private static final TaskDescriptor<Void> PROBE = TaskDescriptor.of("concurrency-probe");
    private static final int JOBS = 18;

    private static final AtomicInteger running = new AtomicInteger();
    private static final AtomicInteger mostRunning = new AtomicInteger();
    private static final AtomicInteger finished = new AtomicInteger();
    private static final AtomicBoolean allVirtual = new AtomicBoolean(true);

    @Autowired
    private SchedulerClient scheduler;

    @Test
    void runsNoMoreJobsAtOnceThanTheConfiguredThreadsOnVirtualThreads() {
        IntStream.range(0, JOBS).forEach(i ->
                scheduler.scheduleIfNotExists(PROBE.instance("job-" + i).scheduledTo(Instant.now())));

        await().atMost(Duration.ofSeconds(30)).until(() -> finished.get() == JOBS);

        assertThat(mostRunning.get()).isEqualTo(3);
        assertThat(allVirtual).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Task<Void> concurrencyProbe() {
            return Tasks.oneTime(PROBE).execute((instance, context) -> {
                mostRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                allVirtual.compareAndSet(true, Thread.currentThread().isVirtual());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                    finished.incrementAndGet();
                }
            });
        }
    }
}
