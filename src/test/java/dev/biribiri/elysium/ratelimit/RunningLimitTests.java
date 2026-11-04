package dev.biribiri.elysium.ratelimit;

import org.junit.jupiter.api.Test;

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
}
