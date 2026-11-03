package dev.biribiri.elysium.auth.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTests {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final AtomicLong now = new AtomicLong();
    private final RateLimiter limiter = new RateLimiter(2, WINDOW, 100, now::get);

    @Test
    void turnsAwayAttemptsOverTheLimit() {
        assertThat(limiter.tryAcquire("key")).isTrue();
        assertThat(limiter.tryAcquire("key")).isTrue();
        assertThat(limiter.tryAcquire("key")).isFalse();
    }

    @Test
    void countsEachKeySeparately() {
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        assertThat(limiter.tryAcquire("other")).isTrue();
    }

    @Test
    void startsAgainWhenTheWindowEnds() {
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        advance(WINDOW.minusNanos(1));
        assertThat(limiter.tryAcquire("key")).isFalse();

        advance(Duration.ofNanos(1));
        assertThat(limiter.tryAcquire("key")).isTrue();
    }

    @Test
    void attemptsDontExtendTheWindow() {
        limiter.tryAcquire("key");
        advance(Duration.ofMinutes(9));
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        assertThat(limiter.timeUntilReset("key")).isEqualTo(Duration.ofMinutes(1));
        advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("key")).isTrue();
    }

    @Test
    void releasingGivesASlotBack() {
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        limiter.release("key");

        assertThat(limiter.tryAcquire("key")).isTrue();
        assertThat(limiter.tryAcquire("key")).isFalse();
    }

    @Test
    void releasingMoreThanWasTakenDoesntAddSlots() {
        limiter.tryAcquire("key");
        limiter.release("key");
        limiter.release("key");
        limiter.release("unknown");

        assertThat(limiter.tryAcquire("key")).isTrue();
        assertThat(limiter.tryAcquire("key")).isTrue();
        assertThat(limiter.tryAcquire("key")).isFalse();
    }

    @Test
    void resettingForgetsTheCount() {
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        limiter.reset("key");

        assertThat(limiter.tryAcquire("key")).isTrue();
        assertThat(limiter.tryAcquire("key")).isTrue();
    }

    @Test
    void reportsNoWaitForAKeyWithNoWindow() {
        assertThat(limiter.timeUntilReset("key")).isEqualTo(Duration.ZERO);

        limiter.tryAcquire("key");
        advance(WINDOW);

        assertThat(limiter.timeUntilReset("key")).isEqualTo(Duration.ZERO);
    }

    @Test
    void tracksABoundedNumberOfKeys() {
        IntStream.range(0, 1_000).forEach(i -> limiter.tryAcquire("key" + i));

        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(100);
    }

    @Test
    void dropsKeysAfterTheirWindowEnds() {
        limiter.tryAcquire("key");

        advance(WINDOW);

        assertThat(limiter.trackedKeys()).isZero();
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
