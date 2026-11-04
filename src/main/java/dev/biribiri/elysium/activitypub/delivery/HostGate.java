package dev.biribiri.elysium.activitypub.delivery;

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
 * <p>The limit and the spacing are per node, because sharing them would mean a database write around every request, so the
 * real limit is this many times the number of nodes. Backoff is in the database, so nodes share it and it survives a restart.
 */
@Component
class HostGate {

    static final int MAX_REQUESTS_PER_HOST = HostRequests.MAX_DELIVERIES_PER_HOST;


    /**
     * One failure could just be one bad request, or a brief problem that a retry gets past.
     */
    static final int FAILURES_BEFORE_BACKOFF = 3;

    private static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final JdbcClient jdbc;
    private final HostRequests requests;

    /**
     * Lets about as many waiting jobs come due between polls as can get a slot. All the jobs due at a poll run within moments,
     * before any request has finished, so any more would only be refused, with each refusal writing to the database. For a
     * host that answers slowly, this still limits how often its jobs run.
     */
    private final Duration turnSpacing;

    /**
     * The last turn given out for each host. A host only stays here while that's in the future.
     */
    private final Map<String, Instant> lastTurns = new HashMap<>();

    HostGate(
            JdbcClient jdbc,
            HostRequests requests,
            @Value("${db-scheduler.polling-interval}") Duration pollingInterval) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.turnSpacing = pollingInterval.dividedBy(MAX_REQUESTS_PER_HOST);
    }

    /**
     * Takes a slot for a request, if there's one free. Every successful call needs a call to {@link #release}.
     */
    boolean tryAcquire(String host) {
        return requests.tryStartDelivery(host);
    }

    void release(String host) {
        requests.finishDelivery(host);
    }

    /**
     * @return when a job that couldn't get a slot should try again, which is after every job already waiting for this host
     */
    synchronized Instant nextTurn(String host, Instant now) {
        lastTurns.values().removeIf(now::isAfter);
        var last = lastTurns.getOrDefault(host, now);
        var turn = (last.isAfter(now) ? last : now).plus(turnSpacing);
        lastTurns.put(host, turn);
        return turn;
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
