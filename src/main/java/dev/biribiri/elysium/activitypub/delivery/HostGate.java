package dev.biribiri.elysium.activitypub.delivery;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.biribiri.elysium.activitypub.http.HostRequests;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps this server from overloading others: it limits how many requests run at once for each host, spaces out the jobs
 * waiting for one, and backs off from hosts that keep failing.
 *
 * <p>The spacing follows how fast each host answers, so that a backlog for a fast host drains as fast as its requests can run,
 * and one for a slow host doesn't keep running jobs that can't send. Jobs due at the same poll start within moments, so those
 * that find every slot taken wait in their thread for a while, as requests finish.
 *
 * <p>The limit, the spacing, and what's known of each host's speed are per node, because sharing them would mean a database
 * write around every request, so the real limit is this many times the number of nodes. Backoff is in the database, so nodes
 * share it and it survives a restart.
 */
@Component
class HostGate {

    static final int MAX_REQUESTS_PER_HOST = HostRequests.MAX_DELIVERIES_PER_HOST;

    /**
     * The most jobs that can come due for one host at each poll: those that can send at once, and those that can wait in
     * their threads for a slot.
     */
    static final int MAX_TURNS_PER_POLL = MAX_REQUESTS_PER_HOST + HostRequests.MAX_DELIVERIES_WAITING_PER_HOST;

    /**
     * How much each request's time moves a host's average. Answers vary, so one slow one shouldn't decide the spacing.
     */
    private static final double LATENCY_WEIGHT = 0.2;
    private static final long MAX_TRACKED_HOSTS = 10_000;

    /**
     * One failure could just be one bad request, or a brief problem that a retry gets past.
     */
    static final int FAILURES_BEFORE_BACKOFF = 3;

    private static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final JdbcClient jdbc;
    private final HostRequests requests;

    private final Duration pollingInterval;

    /**
     * The last turn given out for each host. A host only stays here while that's in the future.
     */
    private final Map<String, Instant> lastTurns = new HashMap<>();

    /**
     * Each host's average request time, in nanoseconds.
     */
    private final Cache<String, Double> latencies = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_HOSTS)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    HostGate(
            JdbcClient jdbc,
            HostRequests requests,
            @Value("${db-scheduler.polling-interval}") Duration pollingInterval) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.pollingInterval = pollingInterval;
    }

    /**
     * Takes a slot for a request, if there's one free. Every successful call needs a call to {@link #release}.
     */
    boolean tryAcquire(String host) {
        return requests.tryStartDelivery(host);
    }

    /**
     * Like {@link #tryAcquire}, but waits for a slot until about when the next poll could start the job again.
     */
    boolean tryAcquireWaiting(String host) {
        return requests.tryStartDelivery(host, pollingInterval);
    }

    void release(String host) {
        requests.finishDelivery(host);
    }

    /**
     * Records how long a request that this host answered took. One that failed could have failed at once, which says nothing
     * about its speed, and a host that keeps failing is backed off from anyway.
     */
    void took(String host, Duration time) {
        latencies.asMap().merge(host, (double) time.toNanos(),
                (average, latest) -> average + LATENCY_WEIGHT * (latest - average));
    }

    /**
     * @return when a job that couldn't get a slot should try again, which is after every job already waiting for this host
     */
    synchronized Instant nextTurn(String host, Instant now) {
        lastTurns.values().removeIf(now::isAfter);
        var last = lastTurns.getOrDefault(host, now);
        var turn = (last.isAfter(now) ? last : now).plus(turnSpacing(host));
        lastTurns.put(host, turn);
        return turn;
    }

    /**
     * Lets about as many waiting jobs come due between polls as can get a slot before the next poll, as requests finish. Any
     * more would only be refused, with each refusal writing to the database.
     *
     * <p>A host whose speed isn't known yet gets as many turns as can send or wait. A fan-out books every job's turn before
     * the first answer, and a turn isn't moved once it's booked, so a slower guess would hold a fast host's whole backlog
     * to it. If the host turns out to be slow, the jobs that come due too early are refused after waiting, and take turns
     * again, spaced by then for its speed.
     */
    private Duration turnSpacing(String host) {
        return pollingInterval.dividedBy(turnsPerPoll(latencies.getIfPresent(host), pollingInterval));
    }

    /**
     * @param latency the host's average request time in nanoseconds, or null if it isn't known
     */
    static int turnsPerPoll(Double latency, Duration pollingInterval) {
        if (latency == null) {
            return MAX_TURNS_PER_POLL;
        }
        var fit = Math.floor(MAX_REQUESTS_PER_HOST * pollingInterval.toNanos() / Math.max(1, latency));
        return (int) Math.max(MAX_REQUESTS_PER_HOST, Math.min(MAX_TURNS_PER_POLL, fit));
    }

    /**
     * @return when requests to this host can start again, or empty if they can now
     */
    Optional<Instant> backingOffUntil(String host, Instant now) {
        return jdbc.sql("SELECT retry_after FROM federation_host WHERE host = :host")
                .param("host", host)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .filter(now::isBefore);
    }

    /**
     * Records that a host answered. A refusal counts too, since it shows that the host is working.
     */
    void succeeded(String host) {
        jdbc.sql("DELETE FROM federation_host WHERE host = :host").param("host", host).update();
    }

    /**
     * Records that a host is having trouble.
     *
     * @param retryAfter how long the host asked this server to wait, which is zero if it didn't say
     */
    void failed(String host, Instant now, Duration retryAfter) {
        var failures = jdbc.sql("""
                        INSERT INTO federation_host (host, consecutive_failures, retry_after)
                        VALUES (:host, 1, :now)
                        ON CONFLICT (host) DO UPDATE SET consecutive_failures = federation_host.consecutive_failures + 1
                        RETURNING consecutive_failures
                        """)
                .param("host", host)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        var until = now.plus(longer(backoff(failures), retryAfter));
        // Another request to this host can have failed meanwhile, and it might have been asked to wait for longer.
        jdbc.sql("UPDATE federation_host SET retry_after = GREATEST(retry_after, :until) WHERE host = :host")
                .param("host", host)
                .param("until", until.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static Duration longer(Duration backoff, Duration retryAfter) {
        var limited = retryAfter.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : retryAfter;
        return limited.compareTo(backoff) > 0 ? limited : backoff;
    }

    static Duration backoff(int failures) {
        if (failures < FAILURES_BEFORE_BACKOFF) {
            return Duration.ZERO;
        }
        // The cap on the shift is only to stay in range. MAX_BACKOFF applies long before this.
        var delay = MIN_BACKOFF.multipliedBy(1L << Math.min(failures - FAILURES_BEFORE_BACKOFF, 20));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
