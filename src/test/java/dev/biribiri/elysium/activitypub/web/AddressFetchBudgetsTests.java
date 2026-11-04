package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.signature.KeyFetchBudget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AddressFetchBudgetsTests {

    private static final String ADDRESS = "203.0.113.1";

    private final AtomicLong nanos = new AtomicLong();
    private final AddressFetchBudgets budgets = new AddressFetchBudgets(nanos::get);

    @Test
    void limitsFetchesRunningAtOnce() {
        var budget = budgets.forAddress(ADDRESS);
        var first = budget.tryReserve().orElseThrow();
        budget.tryReserve().orElseThrow();
        budget.tryReserve().orElseThrow();
        budget.tryReserve().orElseThrow();

        assertThat(budget.tryReserve()).isEmpty();
        assertThat(budgets.forAddress("203.0.113.2").tryReserve()).isPresent();

        first.finished();
        assertThat(budget.tryReserve()).isPresent();
    }

    @Test
    void limitsFetchesPerMinuteEvenIfTheyFinish() {
        var budget = budgets.forAddress(ADDRESS);
        for (var i = 0; i < 30; i++) {
            budget.tryReserve().orElseThrow().finished();
        }

        assertThat(budget.tryReserve()).isEmpty();
        assertThat(budgets.timeUntilReset(ADDRESS)).isEqualTo(Duration.ofMinutes(1));

        nanos.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(budget.tryReserve()).isPresent();
    }

    @Test
    void doesNotCountAFetchThatWasNeverMade() {
        var budget = budgets.forAddress(ADDRESS);
        for (var i = 0; i < 100; i++) {
            budget.tryReserve().orElseThrow().cancelled();
        }

        assertThat(budget.tryReserve()).isPresent();
    }

    /**
     * Being turned away by the limit on running fetches shouldn't use up the limit per minute.
     */
    @Test
    void doesNotCountBeingTurnedAway() {
        var budget = budgets.forAddress(ADDRESS);
        var running = new ArrayList<KeyFetchBudget.Reservation>();
        for (var i = 0; i < 4; i++) {
            running.add(budget.tryReserve().orElseThrow());
        }
        for (var i = 0; i < 100; i++) {
            assertThat(budget.tryReserve()).isEmpty();
        }
        running.forEach(KeyFetchBudget.Reservation::finished);

        for (var i = 0; i < 26; i++) {
            budget.tryReserve().orElseThrow().finished();
        }
        assertThat(budget.tryReserve()).isEmpty();
    }
}
