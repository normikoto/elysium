package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.Poll;
import dev.biribiri.elysium.content.PollVote;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PollRepository {

    private final JdbcClient jdbc;

    PollRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A poll as it's stored, without its counts.
     *
     * @param titles its options, in order
     */
    public record Row(
            UUID statusId, UUID authorId, boolean local, boolean multiple, boolean hideTotals, Instant expiresAt,
            Instant closedAt, Instant endedAt, Instant fetchedAt, List<String> titles) {

        public boolean isExpired(Instant now) {
            return isPast(expiresAt, now) || isPast(closedAt, now);
        }

        private static boolean isPast(Instant at, Instant now) {
            return at != null && !at.isAfter(now);
        }
    }

    public record Ended(UUID statusId, UUID authorId, boolean local) {
    }

    /**
     * @param counts each option's votes, as another server gave them, or null for a local poll, whose votes are counted
     *               from what's stored
     */
    public void insert(
            UUID statusId, boolean multiple, boolean hideTotals, Instant expiresAt, Instant closedAt, Integer votersCount,
            Instant fetchedAt, List<String> titles, List<Integer> counts) {
        jdbc.sql("""
                        INSERT INTO poll (status_id, multiple, hide_totals, expires_at, closed_at, voters_count, fetched_at)
                        VALUES (:statusId, :multiple, :hideTotals, :expiresAt, :closedAt, :votersCount, :fetchedAt)
                        """)
                .param("statusId", statusId)
                .param("multiple", multiple)
                .param("hideTotals", hideTotals)
                .param("expiresAt", offset(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("closedAt", offset(closedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("votersCount", votersCount, Types.INTEGER)
                .param("fetchedAt", offset(fetchedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        for (var position = 0; position < titles.size(); position++) {
            jdbc.sql("""
                            INSERT INTO poll_option (status_id, position, title, votes_count)
                            VALUES (:statusId, :position, :title, :votesCount)
                            """)
                    .param("statusId", statusId)
                    .param("position", position)
                    .param("title", titles.get(position))
                    .param("votesCount", counts == null ? null : counts.get(position), Types.INTEGER)
                    .update();
        }
    }

    /**
     * Removes a poll with its votes.
     */
    public boolean delete(UUID statusId) {
        return jdbc.sql("DELETE FROM poll WHERE status_id = :statusId").param("statusId", statusId).update() == 1;
    }

    /**
     * Locks a poll against votes and changes until the transaction ends.
     */
    public Optional<Row> findAndLock(UUID statusId) {
        return find("WHERE p.status_id = :statusId FOR UPDATE OF p", statusId);
    }

    public Optional<Row> find(UUID statusId) {
        return find("WHERE p.status_id = :statusId", statusId);
    }

    private Optional<Row> find(String condition, UUID statusId) {
        var row = jdbc.sql("""
                        SELECT p.status_id, s.actor_id, a.is_local, p.multiple, p.hide_totals, p.expires_at, p.closed_at,
                               p.ended_at, p.fetched_at
                        FROM poll p JOIN status s ON s.id = p.status_id JOIN actor a ON a.id = s.actor_id
                        """ + condition)
                .param("statusId", statusId)
                .query((rs, n) -> new Row(
                        rs.getObject("status_id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getBoolean("is_local"),
                        rs.getBoolean("multiple"),
                        rs.getBoolean("hide_totals"),
                        ContentRows.instantOrNull(rs.getObject("expires_at", OffsetDateTime.class)),
                        ContentRows.instantOrNull(rs.getObject("closed_at", OffsetDateTime.class)),
                        ContentRows.instantOrNull(rs.getObject("ended_at", OffsetDateTime.class)),
                        ContentRows.instantOrNull(rs.getObject("fetched_at", OffsetDateTime.class)),
                        List.of()))
                .optional();
        return row.map(found -> new Row(found.statusId(), found.authorId(), found.local(), found.multiple(),
                found.hideTotals(), found.expiresAt(), found.closedAt(), found.endedAt(), found.fetchedAt(),
                titles(statusId)));
    }

    private List<String> titles(UUID statusId) {
        return jdbc.sql("SELECT title FROM poll_option WHERE status_id = :statusId ORDER BY position")
                .param("statusId", statusId)
                .query(String.class)
                .list();
    }

    /**
     * A local poll counts the votes of accounts that can be seen, as likes do, so that a suspended account's come back if
     * that ends. A remote poll has the counts its server gave.
     *
     * @param viewer whose own votes to include, or null for nobody's
     * @return an entry for each status that has a poll
     */
    public Map<UUID, Poll> findAll(Collection<UUID> statusIds, UUID viewer) {
        if (statusIds.isEmpty()) {
            return Map.of();
        }
        record OptionRow(UUID statusId, boolean local, boolean multiple, boolean hideTotals, Instant expiresAt,
                         Instant closedAt, Integer votersCount, long localVoters, String title, Integer remoteCount,
                         long localCount) {
        }
        var rows = jdbc.sql("""
                        SELECT p.status_id, a.is_local, p.multiple, p.hide_totals, p.expires_at, p.closed_at, p.voters_count,
                               o.title, o.votes_count,
                               (SELECT count(*) FROM poll_vote v JOIN actor va ON va.id = v.voter_id
                                WHERE v.status_id = p.status_id AND v.position = o.position AND %1$s) AS local_count,
                               (SELECT count(DISTINCT v.voter_id) FROM poll_vote v JOIN actor va ON va.id = v.voter_id
                                WHERE v.status_id = p.status_id AND %1$s) AS local_voters
                        FROM poll p
                        JOIN status s ON s.id = p.status_id
                        JOIN actor a ON a.id = s.actor_id
                        JOIN poll_option o ON o.status_id = p.status_id
                        WHERE p.status_id IN (:ids)
                        ORDER BY p.status_id, o.position
                        """.formatted(LikeRepository.eligible("va")))
                .param("ids", statusIds)
                .query((rs, n) -> new OptionRow(
                        rs.getObject("status_id", UUID.class),
                        rs.getBoolean("is_local"),
                        rs.getBoolean("multiple"),
                        rs.getBoolean("hide_totals"),
                        ContentRows.instantOrNull(rs.getObject("expires_at", OffsetDateTime.class)),
                        ContentRows.instantOrNull(rs.getObject("closed_at", OffsetDateTime.class)),
                        rs.getObject("voters_count", Integer.class),
                        rs.getLong("local_voters"),
                        rs.getString("title"),
                        rs.getObject("votes_count", Integer.class),
                        rs.getLong("local_count")))
                .list();
        var own = viewer == null ? Map.<UUID, List<Integer>>of() : ownVotes(statusIds, viewer);
        var grouped = new LinkedHashMap<UUID, List<OptionRow>>();
        rows.forEach(row -> grouped.computeIfAbsent(row.statusId(), id -> new ArrayList<>()).add(row));
        var polls = new HashMap<UUID, Poll>();
        grouped.forEach((statusId, options) -> {
            var first = options.getFirst();
            var counted = options.stream()
                    .map(row -> new Poll.Option(row.title(), first.local() ? (int) row.localCount()
                            : row.remoteCount() == null ? 0 : row.remoteCount()))
                    .toList();
            int voters;
            if (first.local()) {
                voters = (int) first.localVoters();
            } else if (first.votersCount() != null) {
                voters = first.votersCount();
            } else {
                // Each voter chose one option, or at least the most popular one.
                var counts = counted.stream().mapToInt(Poll.Option::votesCount);
                voters = first.multiple() ? counts.max().orElse(0) : counts.sum();
            }
            polls.put(statusId, new Poll(statusId, first.local(), first.multiple(), first.hideTotals(),
                    first.expiresAt(), first.closedAt(), counted, voters,
                    viewer == null ? null : own.getOrDefault(statusId, List.of())));
        });
        return polls;
    }

    private Map<UUID, List<Integer>> ownVotes(Collection<UUID> statusIds, UUID viewer) {
        var votes = new HashMap<UUID, List<Integer>>();
        jdbc.sql("""
                        SELECT status_id, position FROM poll_vote
                        WHERE status_id IN (:ids) AND voter_id = :viewer ORDER BY position
                        """)
                .param("ids", statusIds)
                .param("viewer", viewer)
                .query((rs, n) -> Map.entry(rs.getObject("status_id", UUID.class), rs.getInt("position")))
                .list()
                .forEach(entry -> votes.computeIfAbsent(entry.getKey(), id -> new ArrayList<>()).add(entry.getValue()));
        return votes;
    }

    public List<Integer> votedPositions(UUID statusId, UUID voterId) {
        return jdbc.sql("SELECT position FROM poll_vote WHERE status_id = :statusId AND voter_id = :voterId ORDER BY position")
                .param("statusId", statusId)
                .param("voterId", voterId)
                .query(Integer.class)
                .list();
    }

    /**
     * @return whether it was stored, which it isn't if it's the voter's second vote for this option, or its URI has been
     * stored before
     */
    public boolean insertVote(UUID id, UUID statusId, UUID voterId, int position, URI uri, Instant createdAt) {
        return jdbc.sql("""
                        INSERT INTO poll_vote (id, status_id, voter_id, position, uri, created_at)
                        VALUES (:id, :statusId, :voterId, :position, :uri, :createdAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", id)
                .param("statusId", statusId)
                .param("voterId", voterId)
                .param("position", position)
                .param("uri", uri == null ? null : uri.toString(), Types.VARCHAR)
                .param("createdAt", offset(createdAt))
                .update() == 1;
    }

    /**
     * Adds votes from here to a remote poll's counts, until its server sends counts that include them.
     */
    public void addRemoteVotes(UUID statusId, Collection<Integer> positions, boolean newVoter) {
        jdbc.sql("""
                        UPDATE poll_option SET votes_count = coalesce(votes_count, 0) + 1
                        WHERE status_id = :statusId AND position IN (:positions)
                        """)
                .param("statusId", statusId)
                .param("positions", positions)
                .update();
        if (newVoter) {
            jdbc.sql("UPDATE poll SET voters_count = voters_count + 1 WHERE status_id = :statusId AND voters_count IS NOT NULL")
                    .param("statusId", statusId)
                    .update();
        }
    }

    public void deleteVotes(UUID statusId) {
        jdbc.sql("DELETE FROM poll_vote WHERE status_id = :statusId").param("statusId", statusId).update();
    }

    /**
     * Changes what an edit can change of a local poll without changing its options. A poll that's open again can end
     * again.
     */
    public void update(UUID statusId, boolean hideTotals, Instant expiresAt) {
        jdbc.sql("""
                        UPDATE poll SET hide_totals = :hideTotals, expires_at = :expiresAt,
                                        ended_at = CASE WHEN :expiresAt > now() THEN NULL ELSE ended_at END
                        WHERE status_id = :statusId
                        """)
                .param("statusId", statusId)
                .param("hideTotals", hideTotals)
                .param("expiresAt", offset(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /**
     * Replaces a remote poll's counts and closing time with what its server says now.
     */
    public void refreshRemote(
            UUID statusId, List<Integer> counts, Integer votersCount, Instant expiresAt, Instant closedAt,
            Instant fetchedAt) {
        jdbc.sql("""
                        UPDATE poll SET voters_count = :votersCount, expires_at = :expiresAt, closed_at = :closedAt,
                                        fetched_at = :fetchedAt
                        WHERE status_id = :statusId
                        """)
                .param("statusId", statusId)
                .param("votersCount", votersCount, Types.INTEGER)
                .param("expiresAt", offset(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("closedAt", offset(closedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("fetchedAt", offset(fetchedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        for (var position = 0; position < counts.size(); position++) {
            jdbc.sql("UPDATE poll_option SET votes_count = :count WHERE status_id = :statusId AND position = :position")
                    .param("statusId", statusId)
                    .param("position", position)
                    .param("count", counts.get(position))
                    .update();
        }
    }

    public void markFetched(UUID statusId, Instant fetchedAt) {
        jdbc.sql("UPDATE poll SET fetched_at = :fetchedAt WHERE status_id = :statusId")
                .param("statusId", statusId)
                .param("fetchedAt", offset(fetchedAt))
                .update();
    }

    /**
     * Marks polls whose voting has closed as ended. Polls that another node is ending are skipped, rather than waited
     * for.
     */
    public List<Ended> endDue(Instant now, int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT status_id FROM poll
                            WHERE ended_at IS NULL AND least(closed_at, expires_at) <= :now
                            ORDER BY least(closed_at, expires_at)
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE poll p SET ended_at = :now
                        FROM due, status s JOIN actor a ON a.id = s.actor_id
                        WHERE p.status_id = due.status_id AND s.id = p.status_id
                        RETURNING p.status_id, s.actor_id, a.is_local
                        """)
                .param("now", offset(now))
                .param("limit", limit)
                .query((rs, n) -> new Ended(
                        rs.getObject("status_id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getBoolean("is_local")))
                .list();
    }

    /**
     * Everyone who's voted, in no order.
     */
    public List<UUID> findVoters(UUID statusId) {
        return jdbc.sql("SELECT DISTINCT voter_id FROM poll_vote WHERE status_id = :statusId")
                .param("statusId", statusId)
                .query(UUID.class)
                .list();
    }

    public List<PollVote> findVotes(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT v.id, v.status_id, v.voter_id, v.position, o.title, v.uri, v.created_at
                        FROM poll_vote v
                        JOIN poll_option o ON o.status_id = v.status_id AND o.position = v.position
                        WHERE v.id IN (:ids)
                        ORDER BY v.position
                        """)
                .param("ids", ids)
                .query((rs, n) -> new PollVote(
                        rs.getObject("id", UUID.class),
                        rs.getObject("status_id", UUID.class),
                        rs.getObject("voter_id", UUID.class),
                        rs.getInt("position"),
                        rs.getString("title"),
                        ContentRows.uriOrNull(rs.getString("uri")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM poll_vote WHERE voter_id = :actorId").param("actorId", actorId).update();
    }

    public List<UUID> votersOn(String domain, UUID after, int limit) {
        return jdbc.sql("""
                        SELECT DISTINCT a.id FROM poll_vote v JOIN actor a ON a.id = v.voter_id
                        WHERE a.domain = :domain AND NOT a.is_local AND a.id > :after
                        ORDER BY a.id LIMIT :limit
                        """)
                .param("domain", domain)
                .param("after", after)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public void removeForActors(Collection<UUID> actorIds) {
        jdbc.sql("DELETE FROM poll_vote WHERE voter_id IN (:ids)").param("ids", actorIds).update();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
