package dev.biribiri.elysium.activitypub.signature;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.ratelimit.RunningLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Finds the public key for a key ID. Keys on this server are read from the database, and all others are fetched from
 * their servers and kept in memory, so they're lost on restart and aren't shared between instances.
 *
 * <p>Key IDs are chosen by whoever sends a request, so anyone can make this server look one up. Local checks stop
 * careless requests, but a sender can always make a request that passes them, so the limits here are what really bound
 * the requests this server makes: failures are remembered, each host has a limit, and only so many fetches can run at
 * once, both overall and for each host. A {@link KeyFetchBudget} stops one sender from using up these limits.
 */
@Component
class PublicKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(PublicKeyResolver.class);

    private static final Duration FOUND_TTL = Duration.ofHours(24);
    private static final Duration MISSING_TTL = Duration.ofMinutes(10);
    private static final Duration UNAVAILABLE_TTL = Duration.ofMinutes(1);

    /**
     * Stops a sender with bad signatures from causing a fetch for every request. This applies whether or not the last
     * try worked, so a server that's having trouble doesn't get more requests because of it.
     */
    private static final Duration MIN_AGE_TO_REFRESH = Duration.ofMinutes(5);
    private static final long MAX_CACHED_KEYS = 10_000;
    private static final int MAX_CONCURRENT_FETCHES = 32;

    /**
     * Keeps slow hosts from taking every fetch. A fetch beyond this would only wait for one of the client's connections.
     */
    private static final int MAX_CONCURRENT_FETCHES_PER_HOST = FederationHttpClient.MAX_CONNECTIONS_PER_HOST;
    private static final int MAX_FETCHES_PER_HOST_PER_MINUTE = 60;
    private static final long MAX_TRACKED_HOSTS = 10_000;

    /**
     * How long one request waits for a fetch. It doesn't stop the fetch, which others may be waiting for too.
     */
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private final ActorService actors;
    private final KeyDocuments documents;
    private final Ticker ticker;
    private final Executor executor;
    private final FetchThrottle throttle;
    private final Semaphore fetches = new Semaphore(MAX_CONCURRENT_FETCHES);

    /**
     * A host only has an entry while a fetch holds one of {@link #fetches}, which bounds its size.
     */
    private final RunningLimit fetchesByHost = new RunningLimit(MAX_CONCURRENT_FETCHES_PER_HOST);
    private final AsyncCache<String, Lookup> cache;
    private final ConcurrentHashMap<String, CompletableFuture<Lookup>> refreshes = new ConcurrentHashMap<>();

    /**
     * Refreshes that failed, kept apart from the keys. Marking the failure on a cached key would restart that key's time
     * to live, so forged signatures could keep an old key around for as long as its server is down.
     */
    private final Cache<String, Lookup> failedRefreshes;

    @Autowired
    PublicKeyResolver(ActorService actors, KeyDocuments documents) {
        this(actors, documents, Ticker.systemTicker(), Executors.newVirtualThreadPerTaskExecutor());
    }

    PublicKeyResolver(ActorService actors, KeyDocuments documents, Ticker ticker, Executor executor) {
        this.actors = actors;
        this.documents = documents;
        this.ticker = ticker;
        this.executor = executor;
        this.throttle = new FetchThrottle(
                MAX_FETCHES_PER_HOST_PER_MINUTE, Duration.ofMinutes(1), MAX_TRACKED_HOSTS, ticker);
        // Async, because a blocking cache can hold up unrelated keys while one is being fetched. Fetches that are in
        // progress share one entry, so a burst of requests for a key only makes one fetch.
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_KEYS)
                .ticker(ticker)
                .expireAfter(Expiry.<String, Lookup>writing((keyId, lookup) -> lookup.timeToLive()))
                .buildAsync();
        this.failedRefreshes = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_KEYS)
                .ticker(ticker)
                .expireAfterWrite(MIN_AGE_TO_REFRESH)
                .build();
    }

    /**
     * @throws SignatureVerificationException if the key can't be found, or can't be fetched right now
     */
    ResolvedKey resolve(KeyId keyId, KeyFetchBudget budget) {
        if (keyId.local()) {
            return resolveLocal(keyId);
        }
        return found(keyId, await(cache.get(keyId.value(), (ignored, unused) -> fetch(keyId, budget))));
    }

    /**
     * Tries fetching a key again, after it didn't match a signature. It may have changed since it was fetched. If the
     * key was fetched recently, or a refresh was tried recently, this doesn't fetch it again.
     *
     * <p>Anyone can cause a refresh by sending a bad signature, so only a {@code 404} or {@code 410} forgets the key.
     * Otherwise, a firewall or maintenance page that's answering for a while would stop valid signatures from passing.
     *
     * @param stale the key that didn't match
     * @return the new key, or empty if there isn't a different one to try
     * @throws SignatureVerificationException if the key can't be fetched right now
     */
    Optional<ResolvedKey> refresh(KeyId keyId, ResolvedKey stale, KeyFetchBudget budget) {
        if (keyId.local()) {
            return Optional.empty();
        }
        var current = cache.getIfPresent(keyId.value());
        if (current != null && current.getNow(null) instanceof Found found) {
            if (!found.key().key().equals(stale.key())) {
                // Another request already refreshed it.
                return Optional.of(found.key());
            }
            if (ticker.read() - found.fetchedAtNanos() < MIN_AGE_TO_REFRESH.toNanos()) {
                return Optional.empty();
            }
        }

        var failed = failedRefreshes.getIfPresent(keyId.value());
        if (failed != null) {
            return refreshResult(keyId, stale, failed);
        }

        var refresh = refreshes.computeIfAbsent(keyId.value(), ignored -> fetch(keyId, budget));
        refresh.whenComplete((lookup, failure) -> {
            // Before it stops being in progress, so that a request arriving in between can't start another one.
            switch (lookup) {
                case Found ignored -> cache.put(keyId.value(), CompletableFuture.completedFuture(lookup));
                case Gone ignored -> cache.put(keyId.value(), CompletableFuture.completedFuture(lookup));
                case Missing ignored -> failedRefreshes.put(keyId.value(), lookup);
                case Unavailable ignored -> failedRefreshes.put(keyId.value(), lookup);
                // Being over a limit, which isn't a request to the server.
                case null -> {
                }
            }
            refreshes.remove(keyId.value(), refresh);
        });
        return refreshResult(keyId, stale, await(refresh));
    }

    private static Optional<ResolvedKey> refreshResult(KeyId keyId, ResolvedKey stale, Lookup lookup) {
        if (lookup instanceof Missing) {
            return Optional.empty();
        }
        var key = found(keyId, lookup);
        return key.key().equals(stale.key()) ? Optional.empty() : Optional.of(key);
    }

    private ResolvedKey resolveLocal(KeyId keyId) {
        return actors.findLocalByUri(keyId.document())
                .filter(actor -> actor.publicKeyId().equals(keyId.document() + fragment(keyId)))
                .map(actor -> new ResolvedKey(keyId.value(), actor.uri(), PublicKeyPem.parse(actor.publicKeyPem())))
                .orElseThrow(() -> new SignatureVerificationException(
                        Failure.REJECTED, "No local actor has the key " + keyId.value()));
    }

    private static String fragment(KeyId keyId) {
        var hash = keyId.value().indexOf('#');
        return hash == -1 ? "" : keyId.value().substring(hash);
    }

    /**
     * Starts a fetch, if the limits allow one. A fetch that isn't allowed fails rather than giving a result, so it isn't
     * remembered, and the next request can try again.
     */
    private CompletableFuture<Lookup> fetch(KeyId keyId, KeyFetchBudget budget) {
        var host = keyId.host();
        // In this order for two reasons. A fetch that this server can't run yet isn't the sender's fault, so it doesn't use
        // the sender's budget. And a sender with no budget left can't spend the budget of the host it names.
        if (!fetches.tryAcquire()) {
            return CompletableFuture.failedFuture(new FetchLimitException("Too many fetches at once"));
        }
        if (!fetchesByHost.tryStart(host)) {
            fetches.release();
            return CompletableFuture.failedFuture(new FetchLimitException("Too many fetches running for " + host));
        }
        var reserved = budget.tryReserve();
        if (reserved.isEmpty()) {
            release(host);
            return CompletableFuture.failedFuture(new FetchBudgetException("Sender has caused too many key fetches"));
        }
        var reservation = reserved.get();
        if (!throttle.tryAcquire(host)) {
            reservation.cancelled();
            release(host);
            return CompletableFuture.failedFuture(new FetchLimitException("Too many fetches from " + host));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return lookUp(keyId);
                } finally {
                    reservation.finished();
                    release(host);
                }
            }, executor);
        } catch (RuntimeException e) {
            reservation.cancelled();
            release(host);
            throw e;
        }
    }

    private void release(String host) {
        fetchesByHost.finish(host);
        fetches.release();
    }

    private Lookup lookUp(KeyId keyId) {
        try {
            return new Found(documents.fetch(keyId), ticker.read());
        } catch (KeyFetchException e) {
            log.debug("Couldn't get key {}: {}", keyId.value(), e.getMessage());
            if (e.isGone()) {
                return new Gone();
            }
            return e.isTemporary() ? new Unavailable() : new Missing();
        } catch (RuntimeException e) {
            log.warn("Unexpected failure getting key {}", keyId.value(), e);
            return new Unavailable();
        }
    }

    private static Lookup await(CompletableFuture<Lookup> lookup) {
        try {
            return lookup.get(MAX_WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while fetching a key");
        } catch (TimeoutException e) {
            throw unavailable("Timed out fetching a key");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof FetchBudgetException) {
                throw new SignatureVerificationException(Failure.FETCH_BUDGET_SPENT, e.getCause().getMessage());
            }
            throw unavailable(e.getCause().getMessage());
        }
    }

    private static ResolvedKey found(KeyId keyId, Lookup lookup) {
        return switch (lookup) {
            case Found found -> found.key();
            case Missing ignored -> throw rejected(keyId);
            case Gone ignored -> throw rejected(keyId);
            case Unavailable ignored -> throw unavailable("Couldn't fetch the key " + keyId.value());
        };
    }

    private static SignatureVerificationException rejected(KeyId keyId) {
        return new SignatureVerificationException(Failure.REJECTED, "Couldn't get the key " + keyId.value());
    }

    private static SignatureVerificationException unavailable(String message) {
        return new SignatureVerificationException(Failure.KEY_UNAVAILABLE, message);
    }

    /**
     * Remembered failures are ordinary results rather than failed futures, because the cache drops those straight away.
     */
    private sealed interface Lookup {

        Duration timeToLive();
    }

    private record Found(ResolvedKey key, long fetchedAtNanos) implements Lookup {

        @Override
        public Duration timeToLive() {
            return FOUND_TTL;
        }
    }

    private record Missing() implements Lookup {

        @Override
        public Duration timeToLive() {
            return MISSING_TTL;
        }
    }

    private record Gone() implements Lookup {

        @Override
        public Duration timeToLive() {
            return MISSING_TTL;
        }
    }

    private record Unavailable() implements Lookup {

        @Override
        public Duration timeToLive() {
            return UNAVAILABLE_TTL;
        }
    }

    private static final class FetchLimitException extends RuntimeException {

        FetchLimitException(String message) {
            super(message);
        }
    }

    private static final class FetchBudgetException extends RuntimeException {

        FetchBudgetException(String message) {
            super(message);
        }
    }
}
