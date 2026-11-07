package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TargetFetchTurnsTests {

    private static final URI TARGET = URI.create("https://Victim.example/anything");
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    private final TargetFetchTurns turns = new TargetFetchTurns();

    @Test
    void allowsABurstAndThenSpacesFetchesOut() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            assertThat(turns.take(TARGET, NOW)).contains(NOW);
        }

        assertThat(turns.take(TARGET, NOW)).contains(NOW.plusSeconds(1));
        assertThat(turns.take(URI.create("https://victim.example/other"), NOW)).contains(NOW.plusSeconds(2));
    }

    @Test
    void keepsHostsApart() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(TARGET, NOW);
        }

        assertThat(turns.take(URI.create("https://bystander.example/x"), NOW)).contains(NOW);
    }

    @Test
    void recoversAtTheSteadyRate() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(TARGET, NOW);
        }
        var later = NOW.plusSeconds(10);

        for (var i = 0; i < 10; i++) {
            assertThat(turns.take(TARGET, later)).contains(later);
        }
        assertThat(turns.take(TARGET, later)).contains(later.plusSeconds(1));
    }

    @Test
    void refusesATurnTooFarAwayWithoutTakingIt() {
        var perWait = (int) (TargetFetchTurns.MAX_WAIT.toSeconds() * TargetFetchTurns.FETCHES_PER_MINUTE / 60);
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE + perWait; i++) {
            assertThat(turns.take(TARGET, NOW)).isPresent();
        }

        assertThat(turns.take(TARGET, NOW)).isEmpty();
        assertThat(turns.take(TARGET, NOW.plusSeconds(1))).contains(NOW.plus(TargetFetchTurns.MAX_WAIT).plusSeconds(1));
    }

    @Test
    void aTurnOnlyHoldsForAShortTime() {
        assertThat(TargetFetchTurns.holds(null, NOW)).isFalse();
        assertThat(TargetFetchTurns.holds(NOW.plusSeconds(1), NOW)).isFalse();
        assertThat(TargetFetchTurns.holds(NOW, NOW)).isTrue();
        assertThat(TargetFetchTurns.holds(NOW.minus(TargetFetchTurns.TURN_LASTS), NOW)).isFalse();
        assertThat(TargetFetchTurns.holds(NOW.minus(Duration.ofMinutes(1)), NOW)).isFalse();
    }
}
