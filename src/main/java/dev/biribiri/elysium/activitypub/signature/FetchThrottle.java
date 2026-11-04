package dev.biribiri.elysium.activitypub.signature;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;

/**
 * Limits how often this server fetches from each host, in fixed time windows. Anyone can send a request naming a key on
 * any host, so without this they could use this server to send traffic at another one. This goes by host rather than by
 * host and port, or using different ports would get around it. Hosts are attacker-chosen, so the number tracked is
 * bounded. Counts are kept in memory, so they're lost on restart.
 */
final class FetchThrottle {

    private final int limit;
    private final long windowNanos;
    private final Ticker ticker;
    private final Cache<String, Window> windows;

    FetchThrottle(int limit, Duration window, long maxHosts, Ticker ticker) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.ticker = ticker;
        this.windows = Caffeine.newBuilder().expireAfterWrite(window).maximumSize(maxHosts).ticker(ticker).build();
    }

    boolean tryAcquire(String host) {
        var acquired = new boolean[1];
        windows.asMap().compute(host, (ignored, current) -> {
            var now = ticker.read();
            var active = current == null || now - current.start() >= windowNanos ? new Window(now, 0) : current;
            acquired[0] = active.count() < limit;
            return acquired[0] ? new Window(active.start(), active.count() + 1) : active;
        });
        return acquired[0];
    }

    private record Window(long start, int count) {
    }
}
