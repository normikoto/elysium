package dev.biribiri.elysium.activitypub.jobs;

import com.github.kagkarlsson.scheduler.Scheduler;
import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commands run with this setting, because they'd otherwise pick up jobs and exit in the middle of them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.jobs.worker-enabled=false")
class JobWorkerDisabledTests {

    @Autowired
    private Scheduler scheduler;

    @Test
    void theWorkerDoesNotStart() {
        assertThat(scheduler.getSchedulerState().isStarted()).isFalse();
    }
}
