package dev.biribiri.elysium.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RunningLimitTests {

    private final RunningLimit limit = new RunningLimit(2);

    @Test
    void turnsAwayAKeyAtItsLimitUntilSomethingFinishes() {
        assertThat(limit.tryStart("key")).isTrue();
        assertThat(limit.tryStart("key")).isTrue();
        assertThat(limit.tryStart("key")).isFalse();

        limit.finish("key");

        assertThat(limit.tryStart("key")).isTrue();
    }

    @Test
    void aLowerLimitCountsWhatsRunningUnderTheSameKey() {
        limit.tryStart("key");

        assertThat(limit.tryStart("key", 1)).isFalse();
        assertThat(limit.tryStart("key")).isTrue();
    }

    @Test
    void countsEachKeySeparately() {
        limit.tryStart("key");
        limit.tryStart("key");

        assertThat(limit.tryStart("other")).isTrue();
    }

    @Test
    void forgetsAKeyWithNothingRunning() {
        limit.tryStart("key");
        limit.tryStart("key");
        limit.tryStart("key");

        limit.finish("key");
        limit.finish("key");

        assertThat(limit.trackedKeys()).isZero();
    }

    @Test
    void aWaitGivesUpWhenNothingFinishes() {
        limit.tryStart("key");
        limit.tryStart("key");
        var started = System.nanoTime();

        assertThat(limit.tryStart("key", 2, Duration.ofMillis(100))).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    }

    @Test
    void aWaitEndsWhenSomethingFinishes() throws Exception {
        limit.tryStart("key");
        limit.tryStart("key");

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = threads.submit(() -> limit.tryStart("key", 2, Duration.ofSeconds(10)));
            Thread.sleep(50);
            var started = System.nanoTime();
            limit.finish("key");

            assertThat(waiting.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
        assertThat(limit.tryStart("key")).isFalse();
    }

    @Test
    void aFinishOnlyLetsOneWaiterIn() throws Exception {
        limit.tryStart("key");
        limit.tryStart("key");

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> limit.tryStart("key", 2, Duration.ofMillis(500)));
            var second = threads.submit(() -> limit.tryStart("key", 2, Duration.ofMillis(500)));
            Thread.sleep(50);
            limit.finish("key");

            assertThat(first.get() ^ second.get()).isTrue();
        }
    }

    @Test
    void aWaitIsNotEndedByAnotherKey() {
        limit.tryStart("key");
        limit.tryStart("key");
        limit.tryStart("other");

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = threads.submit(() -> limit.tryStart("key", 2, Duration.ofMillis(200)));
            limit.finish("other");

            assertThat(waiting).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(false);
        }
    }
}
