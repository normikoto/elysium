package dev.biribiri.elysium.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import({TestcontainersConfiguration.class, HostBusyWaitIntegrationTests.Config.class})
@SpringBootTest(properties = "db-scheduler.polling-interval=PT0.1S")
class HostBusyWaitIntegrationTests {

    private static final TaskDescriptor<ProbeJob> PROBE = TaskDescriptor.of("host-busy-probe", ProbeJob.class);
    private static final List<Instant> runs = new CopyOnWriteArrayList<>();
    private static final List<Boolean> onJobThread = new CopyOnWriteArrayList<>();

    @Autowired
    private SchedulerClient scheduler;

    @Test
    void waitsForABusyHostWithoutFailing() {
        Jobs.schedule(scheduler, PROBE, new ProbeJob(Instant.now()), "probe");

        await().atMost(Duration.ofSeconds(10)).until(() -> runs.size() == 1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var waiting = scheduler.getScheduledExecution(PROBE.instanceId(Jobs.instanceId("probe"))).orElseThrow();
            assertThat(waiting.getConsecutiveFailures()).isZero();
            assertThat(waiting.getLastFailure()).isNull();
            assertThat(waiting.getExecutionTime()).isAfterOrEqualTo(runs.getFirst().plus(Jobs.MIN_HOST_WAIT));
        });

        await().atMost(Duration.ofSeconds(20)).until(() -> runs.size() == 2
                && scheduler.getScheduledExecution(PROBE.instanceId(Jobs.instanceId("probe"))).isEmpty());
        assertThat(Duration.between(runs.get(0), runs.get(1))).isGreaterThanOrEqualTo(Jobs.MIN_HOST_WAIT);
        assertThat(onJobThread).containsOnly(true);
        assertThat(Jobs.isJobThread()).isFalse();
    }

    record ProbeJob(Instant queuedAt) implements QueuedJob {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Task<ProbeJob> hostBusyProbe() {
            return Jobs.task(PROBE, job -> {
                runs.add(Instant.now());
                onJobThread.add(Jobs.isJobThread());
                if (runs.size() == 1) {
                    throw new BusyException("Too many fetches running for remote.example");
                }
                return Jobs.done();
            });
        }
    }
}
