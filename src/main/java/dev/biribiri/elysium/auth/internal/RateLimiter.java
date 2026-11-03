package dev.biribiri.elysium.auth.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.Optional;

/**
 * Counts attempts per key in fixed time windows, so a burst across the end of a window can reach twice the limit. Counts
 * are kept in memory, so they're lost on restart and aren't shared between instances. The number of keys is bounded
 * because callers can be made to track attacker-chosen values, so a flood of new keys can evict old ones.
 */
final class RateLimiter {

    private final int limit;
    private final long windowNanos;
    private final Ticker ticker;
    private final Cache<String, Window> windows;

    RateLimiter(int limit, Duration window, long maxKeys, Ticker ticker) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.ticker = ticker;
        this.windows = Caffeine.newBuilder()
                // Each entry checks its own window. This only removes the ones that have ended.
                .expireAfterWrite(window)
                .maximumSize(maxKeys)
                .ticker(ticker)
                .build();
    }

    /**
     * Takes a slot if the key has one left. A request that's turned away doesn't use a slot, so retrying doesn't extend
     * the wait.
     *
     * @return the slot, or empty if there's none left
     */
    Optional<Slot> tryAcquire(String key) {
        var acquired = new Slot[1];
        windows.asMap().compute(key, (ignored, current) -> {
            var now = ticker.read();
            var active = current == null || hasEnded(current, now) ? new Window(now, 0) : current;
            if (active.count() >= limit) {
                return active;
            }
            acquired[0] = new Slot(key, active.start());
            return new Window(active.start(), active.count() + 1);
        });
        return Optional.ofNullable(acquired[0]);
    }

    /**
     * How long until the key's current window ends, or zero if it has none.
     */
    Duration timeUntilReset(String key) {
        var current = windows.getIfPresent(key);
        var now = ticker.read();
        if (current == null || hasEnded(current, now)) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(windowNanos - (now - current.start()));
    }

    long trackedKeys() {
        windows.cleanUp();
        return windows.estimatedSize();
    }

    private boolean hasEnded(Window window, long now) {
        return now - window.start() >= windowNanos;
    }

    private record Window(long start, int count) {
    }

    /**
     * One attempt counted against a key.
     */
    final class Slot {

        private final String key;
        private final long windowStart;

        private Slot(String key, long windowStart) {
            this.key = key;
            this.windowStart = windowStart;
        }

        /**
         * Gives the slot back. If its window has ended this does nothing, because an attempt that waited a long time
         * shouldn't erase one counted in the next window.
         */
        void release() {
            windows.asMap().computeIfPresent(key, (ignored, current) -> current.start() == windowStart
                    ? new Window(current.start(), Math.max(0, current.count() - 1))
                    : current);
        }

        /**
         * Forgets everything counted for this slot's key.
         */
        void resetKey() {
            windows.invalidate(key);
        }
    }
}
