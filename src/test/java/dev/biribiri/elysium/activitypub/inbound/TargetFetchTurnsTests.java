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
            assertThat(turns.take(TARGET, null, NOW)).contains(NOW);
        }

        assertThat(turns.take(TARGET, null, NOW)).contains(NOW.plusSeconds(1));
        assertThat(turns.take(URI.create("https://victim.example/other"), null, NOW)).contains(NOW.plusSeconds(2));
    }

    @Test
    void keepsHostsApart() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(TARGET, null, NOW);
        }

        assertThat(turns.take(URI.create("https://bystander.example/x"), null, NOW)).contains(NOW);
    }

    @Test
    void recoversAtTheSteadyRate() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(TARGET, null, NOW);
        }
        var later = NOW.plusSeconds(10);

        for (var i = 0; i < 10; i++) {
            assertThat(turns.take(TARGET, null, later)).contains(later);
        }
        assertThat(turns.take(TARGET, null, later)).contains(later.plusSeconds(1));
    }

    @Test
    void refusesATurnTooFarAwayWithoutTakingIt() {
        var perWait = (int) (TargetFetchTurns.MAX_WAIT.toSeconds() * TargetFetchTurns.FETCHES_PER_MINUTE / 60);
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE + perWait; i++) {
            assertThat(turns.take(TARGET, null, NOW)).isPresent();
        }

        assertThat(turns.take(TARGET, null, NOW)).isEmpty();
        assertThat(turns.take(TARGET, null, NOW.plusSeconds(1))).contains(NOW.plus(TargetFetchTurns.MAX_WAIT).plusSeconds(1));
    }

    @Test
    void aTurnHeldByAJobOnlyLastsAShortTime() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(TARGET, null, NOW);
        }

        assertThat(turns.take(TARGET, NOW, NOW)).contains(NOW);
        assertThat(turns.take(TARGET, NOW.minusSeconds(1), NOW)).contains(NOW);
        // Not yet its turn, or too long after it, so it waits like any other.
        assertThat(turns.take(TARGET, NOW.plusSeconds(5), NOW)).contains(NOW.plusSeconds(1));
        assertThat(turns.take(TARGET, NOW.minus(TargetFetchTurns.TURN_LASTS), NOW)).contains(NOW.plusSeconds(2));
        assertThat(turns.take(TARGET, NOW.minus(Duration.ofMinutes(1)), NOW)).contains(NOW.plusSeconds(3));
    }
}
