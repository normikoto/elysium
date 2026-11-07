package dev.biribiri.elysium.ratelimit;

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
    void roundsRetryAfterUp() {
        assertThat(RateLimiter.retryAfterSeconds(Duration.ofSeconds(90))).isEqualTo("90");
        assertThat(RateLimiter.retryAfterSeconds(Duration.ofMillis(90_001))).isEqualTo("91");
        assertThat(RateLimiter.retryAfterSeconds(Duration.ZERO)).isEqualTo("1");
    }

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

    @Test
    void countsAnAttemptByItsWeight() {
        var bytes = new RateLimiter(100, WINDOW, 100, now::get);

        assertThat(bytes.tryAcquire("key", 60)).isPresent();
        assertThat(bytes.tryAcquire("key", 40)).isPresent();
        assertThat(bytes.tryAcquire("key", 1)).isEmpty();
    }

    @Test
    void turnsAwayAnAttemptHeavierThanWhatsLeftWithoutUsingAny() {
        var bytes = new RateLimiter(100, WINDOW, 100, now::get);
        bytes.tryAcquire("key", 60);

        assertThat(bytes.tryAcquire("key", 50)).isEmpty();
        assertThat(bytes.tryAcquire("key", 40)).isPresent();
    }

    @Test
    void releasingGivesBackAnAttemptsWholeWeight() {
        var bytes = new RateLimiter(100, WINDOW, 100, now::get);
        bytes.tryAcquire("key", 30);
        var slot = bytes.tryAcquire("key", 70).orElseThrow();

        slot.release();

        assertThat(bytes.tryAcquire("key", 70)).isPresent();
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
