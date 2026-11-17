package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RefusedDeliveriesTests {

    private static final String ADDRESS = "203.0.113.1";

    private final AtomicLong nanos = new AtomicLong();
    private final RefusedDeliveries refusals = new RefusedDeliveries(3, nanos::get);

    @Test
    void turnsAnAddressAwayForTheRestOfTheMinuteOnceItsOverTheLimit() {
        refusals.count(ADDRESS, Failure.NO_SUPPORTED_SIGNATURE);
        refusals.count(ADDRESS, Failure.REJECTED);
        assertThat(refusals.isOverLimit(ADDRESS)).isFalse();

        refusals.countTooLarge(ADDRESS);
        assertThat(refusals.isOverLimit(ADDRESS)).isTrue();
        assertThat(refusals.isOverLimit("203.0.113.2")).isFalse();
        assertThat(refusals.retryAfter(ADDRESS)).isEqualTo("60");

        nanos.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(refusals.isOverLimit(ADDRESS)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"MALFORMED", "NO_SUPPORTED_SIGNATURE", "REJECTED"})
    void countsWhatAnyoneCanSend(Failure failure) {
        for (var i = 0; i < 3; i++) {
            refusals.count(ADDRESS, failure);
        }

        assertThat(refusals.isOverLimit(ADDRESS)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"MALFORMED", "NO_SUPPORTED_SIGNATURE", "REJECTED"})
    void doesNotCountWhatRealServersCause(Failure failure) {
        for (var i = 0; i < 100; i++) {
            refusals.count(ADDRESS, failure);
        }

        assertThat(refusals.isOverLimit(ADDRESS)).isFalse();
    }
}
