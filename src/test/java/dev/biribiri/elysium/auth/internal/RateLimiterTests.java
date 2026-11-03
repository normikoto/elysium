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
        assertThat(limiter.tryAcquire("key")).isPresent();
        assertThat(limiter.tryAcquire("key")).isPresent();
        assertThat(limiter.tryAcquire("key")).isEmpty();
    }

    @Test
    void countsEachKeySeparately() {
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        assertThat(limiter.tryAcquire("other")).isPresent();
    }

    @Test
    void startsAgainWhenTheWindowEnds() {
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        advance(WINDOW.minusNanos(1));
        assertThat(limiter.tryAcquire("key")).isEmpty();

        advance(Duration.ofNanos(1));
        assertThat(limiter.tryAcquire("key")).isPresent();
    }

    @Test
    void attemptsDontExtendTheWindow() {
        limiter.tryAcquire("key");
        advance(Duration.ofMinutes(9));
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        assertThat(limiter.timeUntilReset("key")).isEqualTo(Duration.ofMinutes(1));
        advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("key")).isPresent();
    }

    @Test
    void releasingGivesASlotBack() {
        limiter.tryAcquire("key");
        var slot = limiter.tryAcquire("key").orElseThrow();

        slot.release();

        assertThat(limiter.tryAcquire("key")).isPresent();
        assertThat(limiter.tryAcquire("key")).isEmpty();
    }

    @Test
    void releasingMoreThanWasTakenDoesntAddSlots() {
        var slot = limiter.tryAcquire("key").orElseThrow();
        slot.release();
        slot.release();

        assertThat(limiter.tryAcquire("key")).isPresent();
        assertThat(limiter.tryAcquire("key")).isPresent();
        assertThat(limiter.tryAcquire("key")).isEmpty();
    }

    @Test
    void releasingASlotFromAnEarlierWindowDoesntAffectTheCurrentOne() {
        var old = limiter.tryAcquire("key").orElseThrow();
        advance(WINDOW);
        limiter.tryAcquire("key");
        limiter.tryAcquire("key");

        old.release();

        assertThat(limiter.tryAcquire("key")).isEmpty();
    }

    @Test
    void resettingForgetsTheCount() {
        limiter.tryAcquire("key");
        var slot = limiter.tryAcquire("key").orElseThrow();

        slot.resetKey();

        assertThat(limiter.tryAcquire("key")).isPresent();
        assertThat(limiter.tryAcquire("key")).isPresent();
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
