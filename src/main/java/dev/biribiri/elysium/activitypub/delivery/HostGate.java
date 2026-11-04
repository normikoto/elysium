package dev.biribiri.elysium.activitypub.delivery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps this server from overloading others: it limits how many requests run at once for each host, and backs off from
 * hosts that keep failing.
 *
 * <p>The limit is per node, because sharing it would mean a database write around every request, so the real limit is this
 * many times the number of nodes. Backoff is in the database, so nodes share it and it survives a restart.
 */
@Component
class HostGate {

    /**
     * Matches the connections the HTTP client allows to one host. Any more would only wait for a connection.
     */
    static final int MAX_REQUESTS_PER_HOST = 4;

    /**
     * One failure could just be one bad request, or a brief problem that a retry gets past.
     */
    static final int FAILURES_BEFORE_BACKOFF = 3;

    private static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final JdbcClient jdbc;
    private final Map<String, Integer> running = new ConcurrentHashMap<>();

    HostGate(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes a slot for a request, if there's one free. Every successful call needs a call to {@link #release}.
     */
    boolean tryAcquire(String host) {
        var acquired = new boolean[1];
        running.compute(host, (ignored, count) -> {
            var current = count == null ? 0 : count;
            acquired[0] = current < MAX_REQUESTS_PER_HOST;
            return acquired[0] ? current + 1 : current;
        });
        return acquired[0];
    }

    void release(String host) {
        running.computeIfPresent(host, (ignored, count) -> count == 1 ? null : count - 1);
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
     * @param retryAfter how long the host asked this server to wait, or null if it didn't say
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
        jdbc.sql("UPDATE federation_host SET retry_after = :until WHERE host = :host")
                .param("host", host)
                .param("until", until.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static Duration longer(Duration backoff, Duration retryAfter) {
        if (retryAfter == null) {
            return backoff;
        }
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
