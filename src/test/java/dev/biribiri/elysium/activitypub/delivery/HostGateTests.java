package dev.biribiri.elysium.activitypub.delivery;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HostGateTests {

    private static final Duration POLL = Duration.ofSeconds(1);

    @Test
    void aHostWhoseSpeedIsNotKnownGetsAsManyTurnsAsCanSendOrWait() {
        assertThat(HostGate.turnsPerPoll(null, POLL)).isEqualTo(HostGate.MAX_TURNS_PER_POLL);
    }

    @Test
    void aHostGetsAsManyTurnsAsItsSlotsCanServeBeforeTheNextPoll() {
        assertThat(HostGate.turnsPerPoll(millis(250), POLL)).isEqualTo(HostGate.MAX_REQUESTS_PER_HOST * 4);
    }

    @Test
    void aFastHostGetsNoMoreTurnsThanCanSendOrWait() {
        assertThat(HostGate.turnsPerPoll(millis(1), POLL)).isEqualTo(HostGate.MAX_TURNS_PER_POLL);
        assertThat(HostGate.turnsPerPoll(0.0, POLL)).isEqualTo(HostGate.MAX_TURNS_PER_POLL);
    }

    @Test
    void aSlowHostGetsNoFewerTurnsThanItHasSlots() {
        assertThat(HostGate.turnsPerPoll(millis(10_000), POLL)).isEqualTo(HostGate.MAX_REQUESTS_PER_HOST);
    }

    private static Double millis(long millis) {
        return (double) Duration.ofMillis(millis).toNanos();
    }
}
