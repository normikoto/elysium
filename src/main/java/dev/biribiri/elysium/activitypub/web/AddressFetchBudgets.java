package dev.biribiri.elysium.activitypub.web;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.activitypub.signature.KeyFetchBudget;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import dev.biribiri.elysium.ratelimit.RunningLimit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Gives each address a limit on the key fetches it can cause. This goes by address because a sender can pick the host and
 * the key, and can pass or fail a signature as it likes.
 *
 * <p>Both limits are needed. Fetches can take up to a minute, so without a limit on running fetches, one address could hold
 * nearly all of them by answering slowly. Without a limit per minute, one that answers quickly could keep fetching forever,
 * and push real keys out of the cache.
 */
@Component
class AddressFetchBudgets {

    private static final int MAX_STARTED_PER_MINUTE = 30;
    /** The same as one host can have, so a real server's own limit is reached first. */
    private static final int MAX_RUNNING = 4;
    private static final int MAX_TRACKED_ADDRESSES = 100_000;

    private final RateLimiter started;
    private final RunningLimit running = new RunningLimit(MAX_RUNNING);

    @Autowired
    AddressFetchBudgets() {
        this(Ticker.systemTicker());
    }

    AddressFetchBudgets(Ticker ticker) {
        this.started = new RateLimiter(MAX_STARTED_PER_MINUTE, Duration.ofMinutes(1), MAX_TRACKED_ADDRESSES, ticker);
    }

    /**
     * @param address the client's key, from {@code ClientAddresses}
     */
    KeyFetchBudget forAddress(String address) {
        return () -> {
            if (!running.tryStart(address)) {
                return Optional.empty();
            }
            var slot = started.tryAcquire(address);
            if (slot.isEmpty()) {
                running.finish(address);
                return Optional.empty();
            }
            return Optional.of(new KeyFetchBudget.Reservation() {
                @Override
                public void finished() {
                    running.finish(address);
                }

                @Override
                public void cancelled() {
                    running.finish(address);
                    slot.get().release();
                }
            });
        };
    }

    /**
     * @return when to suggest trying again. If it's a running fetch that's in the way, then this can be sooner.
     */
    Duration timeUntilReset(String address) {
        return started.timeUntilReset(address);
    }
}
