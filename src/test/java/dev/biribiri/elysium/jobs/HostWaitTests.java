package dev.biribiri.elysium.jobs;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HostWaitTests {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void waitsTheShortestTimeForANewJob() {
        assertThat(waitFor(Duration.ZERO)).isBetween(Jobs.MIN_HOST_WAIT, Jobs.MIN_HOST_WAIT.multipliedBy(6).dividedBy(5));
    }

    @Test
    void waitsATenthOfAnOlderJobsAge() {
        var age = Duration.ofMinutes(5);

        assertThat(waitFor(age)).isBetween(age.dividedBy(10), age.dividedBy(10).multipliedBy(6).dividedBy(5));
    }

    @Test
    void waitsNoLongerThanTheLongestTime() {
        assertThat(waitFor(Duration.ofDays(1)))
                .isBetween(Jobs.MAX_HOST_WAIT, Jobs.MAX_HOST_WAIT.multipliedBy(6).dividedBy(5));
    }

    private static Duration waitFor(Duration age) {
        QueuedJob job = () -> NOW.minus(age);
        return Duration.between(NOW, Jobs.hostWaitEnds(job, NOW));
    }
}
