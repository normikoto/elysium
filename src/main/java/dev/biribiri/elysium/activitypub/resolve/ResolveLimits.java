package dev.biribiri.elysium.activitypub.resolve;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.actor.RemoteActorResolver.ResolveLimitException;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Counts each local account's lookups of accounts and statuses on other servers, together, so that looking up by URL can't
 * make more requests than looking up by address. Its own bean, rather than part of either lookup, so that a test replacing
 * one of them doesn't take the other's limit with it.
 */
@Component
class ResolveLimits {

    private static final int MAX_TRACKED_ACCOUNTS = 10_000;

    private final RateLimiter lookups;

    ResolveLimits() {
        this(Ticker.systemTicker());
    }

    ResolveLimits(Ticker ticker) {
        this.lookups = new RateLimiter(
                RemoteActorResolver.LOOKUPS_PER_MINUTE, Duration.ofMinutes(1), MAX_TRACKED_ACCOUNTS, ticker);
    }

    /**
     * @throws ResolveLimitException if the requester has made too many lookups recently
     */
    void count(UUID requester) {
        if (lookups.tryAcquire(requester.toString()).isEmpty()) {
            throw new ResolveLimitException();
        }
    }
}
