package dev.biribiri.elysium.activitypub.inbound;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Limits the fetches made from each host on another server's word: a forwarded activity's origin, an object that a
 * {@code Delete} which can't be checked otherwise says is gone, the note of a forwarded {@code Create} or {@code Update}
 * that its origin doesn't serve, a status that an {@code Announce} boosts, and the outbox of an actor whose {@code Accept}
 * started a backfill. Whoever sends those chooses the URL, so without this a few servers, each within its own limits in
 * {@link ServerActivityLimits}, could have this server send any number of signed requests to one victim.
 *
 * <p>Each host gets a steady rate with room for a burst, and a fetch over it is given a turn later on rather than dropped,
 * since real traffic from a large server can come in bursts too. A turn more than {@link #MAX_WAIT} away is refused, which
 * bounds the jobs waiting for one host.
 *
 * <p>Each sender also gets only a {@linkplain #SHARES share} of a host's rate, and can wait only a share as long, so that a
 * flood from one server can't make others' fetches from that host wait an hour, or be refused. The turns a flood does get
 * still count against the host when they run, so that the host never gets more than its rate: others wait behind a flood
 * for up to as long as one sender can wait, or about 4 minutes for each flooding server once there are more than 3, and it
 * takes about 16 together to have them refused. Senders are counted as
 * {@link ServerActivityLimits} counts them, so one domain's subdomains share a share. A job that merges what several senders
 * sent, such as one fetch of a parent that several replies wait for, is charged to the first.
 *
 * <p>Turns are kept in the database, so every node shares them and a restart doesn't forget them. A fetch is a request to
 * another server anyway, so a write beside it costs little.
 */
@Component
class TargetFetchTurns {

    static final int FETCHES_PER_MINUTE = 60;
    static final Duration MAX_WAIT = Duration.ofHours(1);

    /**
     * How many senders it takes to use up a host's rate, or the turns it can have waiting.
     */
    static final int SHARES = 4;
    static final Duration MAX_WAIT_PER_SENDER = MAX_WAIT.dividedBy(SHARES);

    /**
     * How long after its turn a job can still use it. Retries are at least a minute apart, so a retry of a fetch that failed
     * has to take a new turn, rather than fetching again outside the limit.
     */
    static final Duration TURN_LASTS = Duration.ofSeconds(30);

    /**
     * The host's own row, which can't be what {@link #senderOf} returns.
     */
    private static final String HOST_ROW = "";

    private static final Limit HOST = new Limit(Duration.ofMinutes(1).dividedBy(FETCHES_PER_MINUTE), FETCHES_PER_MINUTE,
            MAX_WAIT);
    private static final Limit SENDER = new Limit(Duration.ofMinutes(1).dividedBy(FETCHES_PER_MINUTE / SHARES),
            FETCHES_PER_MINUTE / SHARES, MAX_WAIT_PER_SENDER);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    TargetFetchTurns(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * What a sender is charged as, from its actor's or signer's URI.
     */
    static String senderOf(URI actor) {
        return "@" + ServerActivityLimits.serverKey(actor);
    }

    /**
     * Takes a turn to fetch from this host, unless the job already holds one that's current.
     *
     * @param sender what {@link #senderOf} says for the server whose word the fetch is made on, or null for one that only
     *               counts against the host: a fetch from the sender's own server, which can only flood itself, one that
     *               people here started, or a job queued before senders were counted
     * @param held   the turn the job was given before, or null if it hasn't waited for one
     * @return when the fetch can be made, which is {@code now} if there's room, or empty if it would be too long to wait. No
     * turn is taken then.
     */
    Optional<Instant> take(URI target, String sender, Instant held, Instant now) {
        if (held != null && !now.isBefore(held) && now.isBefore(held.plus(TURN_LASTS))) {
            return Optional.of(now);
        }
        var host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        return transactions.execute(status -> take(host, sender, now));
    }

    private Optional<Instant> take(String host, String sender, Instant now) {
        // Every take for this host waits for the one before, so the rows' times are read and written as one step.
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:host, 0))")
                .param("host", host)
                .query(Integer.class)
                .single();
        var stored = jdbc.sql("SELECT sender, next_free FROM fetch_turn WHERE target = :host AND sender IN (:rows)")
                .param("host", host)
                .param("rows", sender == null ? List.of(HOST_ROW) : List.of(HOST_ROW, sender))
                .query((rs, row) -> Map.entry(
                        rs.getString("sender"), rs.getObject("next_free", OffsetDateTime.class).toInstant()))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var hostFree = HOST.free(stored.get(HOST_ROW), now);
        var turn = HOST.turn(hostFree, now);
        if (turn.isEmpty()) {
            return Optional.empty();
        }
        Instant senderFree = null;
        if (sender != null) {
            senderFree = SENDER.free(stored.get(sender), now);
            var senderTurn = SENDER.turn(senderFree, now);
            if (senderTurn.isEmpty()) {
                return Optional.empty();
            }
            turn = senderTurn.get().isAfter(turn.get()) ? senderTurn : turn;
        }
        // Charged for when the fetch runs, which a sender's share can put later than the host's own turn, so that the host
        // still gets no more than its rate.
        save(host, HOST_ROW, (turn.get().isAfter(hostFree) ? turn.get() : hostFree).plus(HOST.spacing()));
        if (sender != null) {
            save(host, sender, senderFree.plus(SENDER.spacing()));
        }
        return turn;
    }

    private void save(String host, String sender, Instant nextFree) {
        jdbc.sql("""
                        INSERT INTO fetch_turn (target, sender, next_free) VALUES (:host, :sender, :nextFree)
                        ON CONFLICT (target, sender) DO UPDATE SET next_free = excluded.next_free
                        """)
                .param("host", host)
                .param("sender", sender)
                .param("nextFree", nextFree.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * A generic cell rate algorithm: {@code free} is when the rate would next be free if nothing used it up.
     */
    private record Limit(Duration spacing, int burst, Duration maxWait) {

        Instant free(Instant stored, Instant now) {
            return stored == null || stored.isBefore(now) ? now : stored;
        }

        Optional<Instant> turn(Instant free, Instant now) {
            var early = free.minus(spacing.multipliedBy(burst - 1));
            var turn = early.isAfter(now) ? early : now;
            return Duration.between(now, turn).compareTo(maxWait) > 0 ? Optional.empty() : Optional.of(turn);
        }
    }
}
