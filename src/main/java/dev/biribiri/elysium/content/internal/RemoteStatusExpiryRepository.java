package dev.biribiri.elysium.content.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Repository
public class RemoteStatusExpiryRepository {

    /**
     * What this module keeps another server's status for, with {@code s} as the status and {@code :before} as the cutoff.
     *
     * <ul>
     *     <li>A thread that someone here took part in, or that's still active, is kept whole, so that neither a local
     *     reply nor a new one from elsewhere loses its parent. Boosts are in threads of their own.</li>
     *     <li>A status that a recent or local boost is of, since the boost goes with it.</li>
     *     <li>A status that any stored status quotes, since the quote would otherwise show it as deleted. The quote is
     *     removed first, when it's old enough, and the status it quoted on a later pass.</li>
     *     <li>A status that's pinned, by its author or anyone else, so that profiles don't lose them.</li>
     * </ul>
     */
    private static final String KEPT_HERE = """
            EXISTS (SELECT 1 FROM status t JOIN actor ta ON ta.id = t.actor_id
                    WHERE t.thread_id = s.thread_id AND (ta.is_local OR t.created_at >= :before))
            OR EXISTS (SELECT 1 FROM status b JOIN actor ba ON ba.id = b.actor_id
                       WHERE b.reblog_of_id = s.id AND (ba.is_local OR b.created_at >= :before))
            OR EXISTS (SELECT 1 FROM status o JOIN actor oa ON oa.id = o.actor_id
                       WHERE o.id = s.reblog_of_id AND oa.is_local)
            OR EXISTS (SELECT 1 FROM status_quote q WHERE q.quoted_status_id = s.id)
            OR EXISTS (SELECT 1 FROM status_quote q JOIN actor qa ON qa.id = q.quoted_account_id
                       WHERE q.status_id = s.id AND qa.is_local)
            OR EXISTS (SELECT 1 FROM status_like l JOIN actor la ON la.id = l.actor_id
                       WHERE l.status_id = s.id AND la.is_local)
            OR EXISTS (SELECT 1 FROM status_reaction r JOIN actor ra ON ra.id = r.actor_id
                       WHERE r.status_id = s.id AND ra.is_local)
            OR EXISTS (SELECT 1 FROM status_bookmark k WHERE k.status_id = s.id)
            OR EXISTS (SELECT 1 FROM status_pin p WHERE p.status_id = s.id)
            OR EXISTS (SELECT 1 FROM poll_vote v JOIN actor va ON va.id = v.voter_id
                       WHERE v.status_id = s.id AND va.is_local)
            OR EXISTS (SELECT 1 FROM status_mention m JOIN actor ma ON ma.id = m.actor_id
                       WHERE m.status_id = s.id AND ma.is_local)
            OR EXISTS (SELECT 1 FROM post_filter_status f WHERE f.status_id = s.id)""";

    private final JdbcClient jdbc;
    private final StatusRepository statuses;

    RemoteStatusExpiryRepository(JdbcClient jdbc, StatusRepository statuses) {
        this.jdbc = jdbc;
        this.statuses = statuses;
    }

    /**
     * @param scannedTo     the last status looked at, or null to start from the oldest
     * @param passStartedAt when the job last started from the oldest, or null if it never has
     */
    public record Cursor(UUID scannedTo, Instant passStartedAt) {
    }

    public Cursor findCursor() {
        return jdbc.sql("SELECT scanned_to, pass_started_at FROM remote_status_expiry")
                .query((rs, row) -> new Cursor(rs.getObject("scanned_to", UUID.class),
                        rs.getObject("pass_started_at", OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("pass_started_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    public void saveCursor(Cursor cursor) {
        jdbc.sql("UPDATE remote_status_expiry SET scanned_to = :scannedTo, pass_started_at = :passStartedAt")
                .param("scannedTo", cursor.scannedTo(), Types.OTHER)
                .param("passStartedAt", cursor.passStartedAt() == null
                        ? null
                        : cursor.passStartedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /**
     * Other servers' statuses stored before the cutoff, after {@code after}, that nothing keeps, oldest first. A status
     * that was backfilled has an ID from when it was published, earlier than when it was stored, so it's passed over until
     * the scan comes back to it. Bounding the ID as well lets the scan stop at the cutoff.
     *
     * @param references the other modules' conditions, as {@link #remove} takes them
     */
    public List<UUID> findExpired(String references, Instant before, UUID after, int limit) {
        return jdbc.sql("SELECT s.id FROM status s JOIN actor a ON a.id = s.actor_id "
                        + "WHERE s.id > :after AND s.id < :idBefore AND NOT a.is_local AND s.created_at < :before "
                        + "AND NOT (" + keep(references) + ") ORDER BY s.id LIMIT :limit")
                .param("after", after == null ? new UUID(0, 0) : after)
                .param("idBefore", StatusIds.firstAt(before))
                .param("before", before.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * Removes those of these statuses that nothing keeps, and the aliases of the threads that leaves empty.
     *
     * <p>Nothing is published for them, and no tombstone or record of a deletion is kept, since their servers haven't
     * deleted them: one that's fetched or delivered again is stored again, and rejoins its thread through its alias.
     * Their attachments' files are found by the media cleanup job.
     *
     * <p>What keeps a status is checked again once the statuses are locked, in a statement of its own, so that it sees
     * whatever committed before then. Anything that refers to a status takes a lock that conflicts, through its foreign
     * key, so it waits for this, or this skips the status. The ID assignment lock keeps a status from joining one of these
     * threads meanwhile, which the thread condition, and removing aliases, rely on. A vote refers to the poll rather than
     * the status, so the polls are locked too.
     *
     * @param references the other modules' conditions, with {@code s.id} as the status
     * @return how many were removed
     */
    @Transactional
    public int remove(List<UUID> ids, String references, Instant before) {
        statuses.lockIdAssignment();
        var candidates = ids.stream().map(UUID::toString).toArray(String[]::new);
        var locked = new HashSet<>(jdbc.sql("""
                        SELECT id FROM status WHERE id = ANY(CAST(:ids AS uuid[])) FOR UPDATE SKIP LOCKED
                        """)
                .param("ids", candidates).query(UUID.class).list());
        var polls = jdbc.sql("SELECT status_id FROM poll WHERE status_id = ANY(CAST(:ids AS uuid[]))")
                .param("ids", candidates).query(UUID.class).list();
        if (!polls.isEmpty()) {
            var lockedPolls = jdbc.sql("""
                            SELECT status_id FROM poll WHERE status_id = ANY(CAST(:ids AS uuid[])) FOR UPDATE SKIP LOCKED
                            """)
                    .param("ids", candidates).query(UUID.class).list();
            polls.stream().filter(poll -> !lockedPolls.contains(poll)).forEach(locked::remove);
        }
        if (locked.isEmpty()) return 0;
        var threads = jdbc.sql("DELETE FROM status s USING actor a WHERE s.id = ANY(CAST(:ids AS uuid[])) "
                        + "AND a.id = s.actor_id AND NOT a.is_local AND s.created_at < :before "
                        + "AND NOT (" + keep(references) + ") RETURNING s.thread_id")
                .param("ids", locked.stream().map(UUID::toString).toArray(String[]::new))
                .param("before", before.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .list();
        if (!threads.isEmpty()) {
            // A muted thread keeps its aliases, so that its mute still applies to statuses of it that come again.
            jdbc.sql("""
                            DELETE FROM status_thread_alias t WHERE t.thread_id = ANY(CAST(:threads AS uuid[]))
                            AND NOT EXISTS (SELECT 1 FROM status s WHERE s.thread_id = t.thread_id)
                            AND NOT EXISTS (SELECT 1 FROM status_thread_mute m WHERE m.thread_id = t.thread_id)
                            """)
                    .param("threads", threads.stream().distinct().map(UUID::toString).toArray(String[]::new))
                    .update();
        }
        return threads.size();
    }

    private static String keep(String references) {
        return references.isEmpty() ? KEPT_HERE : KEPT_HERE + "\nOR " + references;
    }
}
