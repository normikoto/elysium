package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each test uses hosts of its own, since the turns are in a database that other tests share.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TargetFetchTurnsTests {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final int PER_SENDER = TargetFetchTurns.FETCHES_PER_MINUTE / TargetFetchTurns.SHARES;

    @Autowired
    private TargetFetchTurns turns;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private final String victim = "victim-" + UUID.randomUUID() + ".example";
    private final URI target = URI.create("https://" + victim.toUpperCase() + "/anything");

    @Test
    void allowsABurstAndThenSpacesFetchesOut() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            assertThat(turns.take(target, null, null, NOW)).contains(NOW);
        }

        assertThat(turns.take(target, null, null, NOW)).contains(NOW.plusSeconds(1));
        assertThat(turns.take(URI.create("https://" + victim + "/other"), null, null, NOW)).contains(NOW.plusSeconds(2));
    }

    @Test
    void keepsHostsApart() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(target, null, null, NOW);
        }

        assertThat(turns.take(URI.create("https://bystander-" + UUID.randomUUID() + ".example/x"), null, null, NOW))
                .contains(NOW);
    }

    @Test
    void recoversAtTheSteadyRate() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(target, null, null, NOW);
        }
        var later = NOW.plusSeconds(10);

        for (var i = 0; i < 10; i++) {
            assertThat(turns.take(target, null, null, later)).contains(later);
        }
        assertThat(turns.take(target, null, null, later)).contains(later.plusSeconds(1));
    }

    @Test
    void refusesATurnTooFarAwayWithoutTakingIt() {
        var perWait = (int) (TargetFetchTurns.MAX_WAIT.toSeconds() * TargetFetchTurns.FETCHES_PER_MINUTE / 60);
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE + perWait; i++) {
            assertThat(turns.take(target, null, null, NOW)).isPresent();
        }

        assertThat(turns.take(target, null, null, NOW)).isEmpty();
        assertThat(turns.take(target, null, null, NOW.plusSeconds(1)))
                .contains(NOW.plus(TargetFetchTurns.MAX_WAIT).plusSeconds(1));
    }

    @Test
    void aTurnHeldByAJobOnlyLastsAShortTime() {
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(target, null, null, NOW);
        }

        assertThat(turns.take(target, null, NOW, NOW)).contains(NOW);
        assertThat(turns.take(target, null, NOW.minusSeconds(1), NOW)).contains(NOW);
        // Not yet its turn, or too long after it, so it waits like any other.
        assertThat(turns.take(target, null, NOW.plusSeconds(5), NOW)).contains(NOW.plusSeconds(1));
        assertThat(turns.take(target, null, NOW.minus(TargetFetchTurns.TURN_LASTS), NOW)).contains(NOW.plusSeconds(2));
        assertThat(turns.take(target, null, NOW.minus(Duration.ofMinutes(1)), NOW)).contains(NOW.plusSeconds(3));
    }

    @Test
    void aSenderGetsOnlyAShareOfAHostsRate() {
        var flooder = sender("flooder");
        for (var i = 0; i < PER_SENDER; i++) {
            assertThat(turns.take(target, flooder, null, NOW)).contains(NOW);
        }

        assertThat(turns.take(target, flooder, null, NOW))
                .contains(NOW.plus(Duration.ofMinutes(1).dividedBy(PER_SENDER)));
        assertThat(turns.take(target, sender("bystander"), null, NOW)).contains(NOW);
    }

    @Test
    void aFloodFromOneSenderOnlyHoldsOthersUpForAFewMinutes() {
        var flooder = sender("flooder");
        var taken = 0;
        while (turns.take(target, flooder, null, NOW).isPresent()) {
            taken++;
        }

        assertThat(taken).isEqualTo(PER_SENDER
                + (int) (TargetFetchTurns.MAX_WAIT_PER_SENDER.toSeconds() * PER_SENDER / 60));
        // The flood used up the host's burst and these turns of its rate.
        var heldUp = Duration.ofSeconds(taken - TargetFetchTurns.FETCHES_PER_MINUTE + 1);
        assertThat(heldUp).isLessThanOrEqualTo(Duration.ofMinutes(4));
        assertThat(turns.take(target, sender("bystander"), null, NOW)).contains(NOW.plus(heldUp));
    }

    @Test
    void sendersTogetherStillOnlyGetTheHostsRate() {
        for (var i = 0; i < TargetFetchTurns.SHARES; i++) {
            var sender = sender("sender" + i);
            for (var j = 0; j < PER_SENDER; j++) {
                assertThat(turns.take(target, sender, null, NOW)).contains(NOW);
            }
        }

        assertThat(turns.take(target, sender("another"), null, NOW)).contains(NOW.plusSeconds(1));
        assertThat(turns.take(target, null, null, NOW)).contains(NOW.plusSeconds(2));
    }

    @Test
    void aSendersSubdomainsShareItsShare() {
        assertThat(TargetFetchTurns.senderOf(URI.create("https://a.flooder.example/users/x")))
                .isEqualTo(TargetFetchTurns.senderOf(URI.create("https://b.flooder.example/users/y")));
    }

    @Test
    void everyNodeSharesTheTurns() {
        var other = new TargetFetchTurns(jdbc, transactions);
        for (var i = 0; i < TargetFetchTurns.FETCHES_PER_MINUTE; i++) {
            turns.take(target, null, null, NOW);
        }

        assertThat(other.take(target, null, null, NOW)).contains(NOW.plusSeconds(1));
    }

    private static String sender(String name) {
        return TargetFetchTurns.senderOf(URI.create("https://" + name + "-" + UUID.randomUUID() + ".example/actor"));
    }
}
