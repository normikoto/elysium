package dev.biribiri.elysium.activitypub.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTests {

    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"PT0S,PT1M", "PT59S,PT1M", "PT2M,PT2M", "PT1H,PT1H", "PT6H,PT6H", "PT7H,PT6H", "P2D,PT6H"})
    void delaysGrowWithAgeWithinLimits(Duration age, Duration delay) {
        assertThat(RetryPolicy.delayFor(jobAged(age), NOW)).isEqualTo(delay);
    }

    @Test
    void givesUpAfterThreeDays() {
        assertThat(RetryPolicy.isOverdue(jobAged(Duration.ofDays(3).minusSeconds(1)), NOW)).isFalse();
        assertThat(RetryPolicy.isOverdue(jobAged(Duration.ofDays(3)), NOW)).isTrue();
    }

    @Test
    void aLongOutageStillTriesARetryBeforeTheLimit() {
        // Doubling gaps, capped at the longest delay, take about a day to reach it, and then keep trying.
        var tries = 0;
        var age = Duration.ZERO;
        while (!RetryPolicy.isOverdue(jobAged(age), NOW)) {
            age = age.plus(RetryPolicy.delayFor(jobAged(age), NOW));
            tries++;
        }
        assertThat(tries).isBetween(15, 25);
    }

    @Test
    void jobIdsAreStableAndSeparateTheirParts() {
        assertThat(Jobs.instanceId("a", "b")).isEqualTo(Jobs.instanceId("a", "b"));
        assertThat(Jobs.instanceId("a", "bc")).isNotEqualTo(Jobs.instanceId("ab", "c"));
        assertThat(Jobs.instanceId("x".repeat(100_000))).hasSize(64);
    }

    private static QueuedJob jobAged(Duration age) {
        return () -> NOW.minus(age);
    }
}
