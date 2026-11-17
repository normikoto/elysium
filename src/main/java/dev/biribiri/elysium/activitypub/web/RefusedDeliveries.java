package dev.biribiri.elysium.activitypub.web;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.activitypub.inbound.InboxLimitProperties;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Limits the inbox requests that each address can have refused for what anyone can send without a key of their own: no
 * signature, one that can't be read or isn't valid, or a body that's too large. The inbox reads up to a whole body, and
 * checks its digest and signature, before it knows, and these requests have no signer for {@code ServerActivityLimits} to
 * count them against. This counts only refusals, rather than every request as a reverse proxy would, so the many valid
 * deliveries that a large server sends from a few addresses don't count.
 *
 * <p>Other refusals don't count, because real servers cause them. A key that's gone or can't be fetched happens to deleted
 * actors and servers that are down, and a blocked or suspended signer can share its address with servers that aren't, on a
 * host for many servers. Making those cost a sender something is left to {@link AddressFetchBudgets}.
 *
 * <p>The check comes before the body is read, so an address over the limit is turned away for the rest of the minute, valid
 * deliveries included. Senders try again after a {@code 429}, so these aren't lost.
 */
@Component
class RefusedDeliveries {

    private static final int MAX_TRACKED_ADDRESSES = 100_000;

    private final RateLimiter refused;

    @Autowired
    RefusedDeliveries(InboxLimitProperties properties) {
        this(properties.refusedPerMinute(), Ticker.systemTicker());
    }

    RefusedDeliveries(int perMinute, Ticker ticker) {
        this.refused = new RateLimiter(perMinute, Duration.ofMinutes(1), MAX_TRACKED_ADDRESSES, ticker);
    }

    /**
     * @param address the client's key, from {@code ClientAddresses}
     */
    boolean isOverLimit(String address) {
        return !refused.hasRoom(address);
    }

    /**
     * Counts a body that was too large.
     */
    void countTooLarge(String address) {
        refused.tryAcquire(address);
    }

    /**
     * Counts a signature that was refused, if anyone could have sent it.
     */
    void count(String address, Failure failure) {
        switch (failure) {
            case MALFORMED, NO_SUPPORTED_SIGNATURE, REJECTED -> refused.tryAcquire(address);
            case KEY_GONE, DOMAIN_BLOCKED, ACTOR_SUSPENDED, KEY_UNAVAILABLE, FETCH_BUDGET_SPENT -> {
            }
        }
    }

    /**
     * @return the {@code Retry-After} value for a request refused because its address is over the limit
     */
    String retryAfter(String address) {
        return RateLimiter.retryAfterSeconds(refused.timeUntilReset(address));
    }
}
