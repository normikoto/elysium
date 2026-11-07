package dev.biribiri.elysium.activitypub.inbound;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Limits the fetches made from each host on another server's word: a forwarded activity's origin, and an object that a
 * {@code Delete} which can't be checked otherwise says is gone. Whoever sends those chooses the URL, so without this a few
 * servers, each within its own limits in {@link ServerActivityLimits}, could have this server send any number of signed
 * requests to one victim.
 *
 * <p>Each host gets a steady rate with room for a burst, and a fetch over it is given a turn later on rather than dropped,
 * since real traffic from a large server can come in bursts too. A turn more than {@link #MAX_WAIT} away is refused, which
 * bounds the jobs waiting for one host. Like {@code HostGate}, this is per node and in memory, so the real rate is this many
 * times the number of nodes, and a restart forgets the turns given out, though the jobs keep them.
 */
@Component
class TargetFetchTurns {

    static final int FETCHES_PER_MINUTE = 60;
    static final Duration MAX_WAIT = Duration.ofHours(1);

    /**
     * How long after its turn a job can still use it. Retries are at least a minute apart, so a retry of a fetch that failed
     * has to take a new turn, rather than fetching again outside the limit.
     */
    static final Duration TURN_LASTS = Duration.ofSeconds(30);

    private static final Duration SPACING = Duration.ofMinutes(1).dividedBy(FETCHES_PER_MINUTE);
    private static final Duration BURST = SPACING.multipliedBy(FETCHES_PER_MINUTE - 1);
    private static final long MAX_TRACKED_HOSTS = 10_000;

    /**
     * When each host's rate would next be free if nothing used it up, as in a generic cell rate algorithm. A host whose time
     * has passed is no different from one that isn't tracked, so dropping one only ever lets more through. Hosts are
     * attacker-chosen, so the number tracked is bounded.
     */
    private final Cache<String, Instant> nextFree = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_HOSTS)
            .expireAfterWrite(MAX_WAIT.plus(BURST).plus(SPACING))
            .build();

    /**
     * Takes a turn to fetch from this host, unless the job already holds one that's current.
     *
     * @param held the turn the job was given before, or null if it hasn't waited for one
     * @return when the fetch can be made, which is {@code now} if there's room, or empty if it would be too long to wait. No
     * turn is taken then.
     */
    synchronized Optional<Instant> take(URI target, Instant held, Instant now) {
        if (held != null && !now.isBefore(held) && now.isBefore(held.plus(TURN_LASTS))) {
            return Optional.of(now);
        }
        var host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        var current = nextFree.getIfPresent(host);
        var free = current == null || current.isBefore(now) ? now : current;
        var turn = free.minus(BURST).isAfter(now) ? free.minus(BURST) : now;
        if (Duration.between(now, turn).compareTo(MAX_WAIT) > 0) {
            return Optional.empty();
        }
        nextFree.put(host, free.plus(SPACING));
        return Optional.of(turn);
    }
}
