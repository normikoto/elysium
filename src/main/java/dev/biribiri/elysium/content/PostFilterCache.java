package dev.biribiri.elysium.content;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Each account's compiled filters, since every status shown to a user is matched against them, including once for each of
 * their streams for every status that's streamed. Most users have none, and that's kept too, which saves the query.
 * <p>
 * A change here forgets the account's entry once it's committed, since forgetting it before would let a read of what was
 * there before put it back. Another copy of the server isn't told, so an entry is only kept for {@link #MAX_AGE}, and never
 * past when the first of its filters expires.
 */
@Component
public class PostFilterCache {

    static final Duration MAX_AGE = Duration.ofMinutes(1);

    /**
     * Bounds the memory that the most active accounts' filters can take up.
     */
    static final int MAX_ACCOUNTS = 10_000;

    private final Clock clock;
    private final Cache<UUID, PostFilterMatcher> matchers;

    PostFilterCache() {
        this(Ticker.systemTicker(), Clock.systemUTC());
    }

    PostFilterCache(Ticker ticker, Clock clock) {
        this.clock = clock;
        this.matchers = Caffeine.newBuilder()
                .maximumSize(MAX_ACCOUNTS)
                .ticker(ticker)
                .expireAfter(Expiry.creating((UUID account, PostFilterMatcher matcher) -> lifetime(matcher)))
                .build();
    }

    /**
     * @param load reads the account's filters that apply now
     */
    PostFilterMatcher get(UUID accountId, Function<UUID, List<PostFilter>> load) {
        return matchers.get(accountId, account -> {
            var active = load.apply(account);
            return active.isEmpty() ? PostFilterMatcher.NONE : new PostFilterMatcher(active);
        });
    }

    /**
     * Forgets the account's filters once the current transaction commits, or straight away outside of one.
     */
    void forget(UUID accountId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    matchers.invalidate(accountId);
                }
            });
        } else {
            matchers.invalidate(accountId);
        }
    }

    /**
     * For when filters are changed other than through {@link PostFilters}, as tests do.
     */
    public void clear() {
        matchers.invalidateAll();
    }

    private Duration lifetime(PostFilterMatcher matcher) {
        if (matcher.validUntil() == null) return MAX_AGE;
        var left = Duration.between(clock.instant(), matcher.validUntil());
        return left.isNegative() ? Duration.ZERO : left.compareTo(MAX_AGE) < 0 ? left : MAX_AGE;
    }
}
