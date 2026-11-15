package dev.biribiri.elysium.content;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PostFilterCacheTests {

    private final MovingClock clock = new MovingClock();
    private final PostFilterCache cache = new PostFilterCache(clock, clock);
    private final UUID account = UUID.randomUUID();
    private final AtomicInteger loads = new AtomicInteger();

    @Test
    void keepsAnAccountsFiltersUntilTheyreForgotten() {
        var first = load(List.of());
        assertThat(load(List.of())).isSameAs(first);
        assertThat(loads).hasValue(1);

        cache.forget(account);
        load(List.of());
        assertThat(loads).hasValue(2);
    }

    @Test
    void keepsThemNoLongerThanTheFirstOfThemApplies() {
        load(List.of(filter(clock.instant().plusSeconds(10)), filter(null)));
        clock.advance(Duration.ofSeconds(9));
        load(List.of());
        assertThat(loads).hasValue(1);

        clock.advance(Duration.ofSeconds(2));
        load(List.of());
        assertThat(loads).hasValue(2);
    }

    @Test
    void keepsThemForAMinuteAtMost() {
        load(List.of(filter(null)));
        clock.advance(PostFilterCache.MAX_AGE.plusSeconds(1));
        load(List.of());
        assertThat(loads).hasValue(2);
    }

    private PostFilterMatcher load(List<PostFilter> active) {
        return cache.get(account, ignored -> {
            loads.incrementAndGet();
            return active;
        });
    }

    private static PostFilter filter(Instant expiresAt) {
        return new PostFilter(UUID.randomUUID(), "f", Set.of(PostFilter.Context.HOME), PostFilter.Action.WARN, expiresAt,
                List.of(new PostFilter.Keyword(UUID.randomUUID(), "word", true)), List.of());
    }

    /**
     * Both of the cache's clocks, which only move when told to.
     */
    private static final class MovingClock extends Clock implements Ticker {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        private long nanos;

        void advance(Duration duration) {
            now = now.plus(duration);
            nanos += duration.toNanos();
        }

        @Override
        public long read() {
            return nanos;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
