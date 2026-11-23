package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.PostCleanupPolicy;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostCleanupRepository {

    private static final RowMapper<PostCleanupPolicy> POLICY = (rs, row) -> policy(rs);

    private final JdbcClient jdbc;

    PostCleanupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An old status or boost, and whether the policy deletes it.
     */
    public record Scanned(UUID id, boolean boost, boolean deletable) {
    }

    public Optional<PostCleanupPolicy> find(UUID actorId) {
        return jdbc.sql("SELECT * FROM post_cleanup_policy WHERE actor_id = :actorId")
                .param("actorId", actorId).query(POLICY).optional();
    }

    public void save(UUID actorId, PostCleanupPolicy policy) {
        jdbc.sql("""
                        INSERT INTO post_cleanup_policy (actor_id, enabled, min_age_days, keep_pinned, keep_direct,
                                                         keep_polls, keep_media, keep_self_favourited,
                                                         keep_self_bookmarked, min_favourites, min_boosts)
                        VALUES (:actorId, :enabled, :minAgeDays, :keepPinned, :keepDirect, :keepPolls, :keepMedia,
                                :keepSelfFavourited, :keepSelfBookmarked, :minFavourites, :minBoosts)
                        ON CONFLICT (actor_id) DO UPDATE SET
                            enabled = excluded.enabled, min_age_days = excluded.min_age_days,
                            keep_pinned = excluded.keep_pinned, keep_direct = excluded.keep_direct,
                            keep_polls = excluded.keep_polls, keep_media = excluded.keep_media,
                            keep_self_favourited = excluded.keep_self_favourited,
                            keep_self_bookmarked = excluded.keep_self_bookmarked,
                            min_favourites = excluded.min_favourites, min_boosts = excluded.min_boosts,
                            scanned_to = NULL
                        """)
                .param("actorId", actorId)
                .param("enabled", policy.enabled())
                .param("minAgeDays", policy.minimumAge().days())
                .param("keepPinned", policy.keepPinned())
                .param("keepDirect", policy.keepDirect())
                .param("keepPolls", policy.keepPolls())
                .param("keepMedia", policy.keepMedia())
                .param("keepSelfFavourited", policy.keepSelfFavourited())
                .param("keepSelfBookmarked", policy.keepSelfBookmarked())
                .param("minFavourites", policy.minFavourites(), Types.INTEGER)
                .param("minBoosts", policy.minBoosts(), Types.INTEGER)
                .update();
    }

    public void remove(UUID actorId) {
        jdbc.sql("DELETE FROM post_cleanup_policy WHERE actor_id = :actorId").param("actorId", actorId).update();
    }

    /**
     * The accounts whose policies are on, those the job looked at longest ago first.
     */
    public List<UUID> findEnabled(int limit) {
        return jdbc.sql("""
                        SELECT actor_id FROM post_cleanup_policy WHERE enabled
                        ORDER BY last_run_at NULLS FIRST, actor_id LIMIT :limit
                        """)
                .param("limit", limit).query(UUID.class).list();
    }

    public Optional<UUID> findScannedTo(UUID actorId) {
        return jdbc.sql("SELECT scanned_to FROM post_cleanup_policy WHERE actor_id = :actorId")
                .param("actorId", actorId).query((rs, row) -> rs.getObject(1, UUID.class)).optional()
                .flatMap(Optional::ofNullable);
    }

    public void markRun(UUID actorId, Instant at) {
        jdbc.sql("UPDATE post_cleanup_policy SET last_run_at = :at WHERE actor_id = :actorId")
                .param("actorId", actorId).param("at", at.atOffset(ZoneOffset.UTC)).update();
    }

    /**
     * Moves the scan on, unless the policy was saved since it was read, which starts the scan again for the new policy.
     */
    public void markScanned(UUID actorId, UUID from, UUID to) {
        jdbc.sql("""
                        UPDATE post_cleanup_policy SET scanned_to = :to
                        WHERE actor_id = :actorId AND scanned_to IS NOT DISTINCT FROM :from
                        """)
                .param("actorId", actorId).param("from", from, Types.OTHER).param("to", to, Types.OTHER).update();
    }

    /**
     * The account's statuses and boosts published before the cutoff and after {@code after}, oldest first, and whether
     * the policy deletes each. Status IDs sort by when they were published, so bounding the ID as well lets the scan stop
     * at the cutoff.
     *
     * @param after the last status an earlier scan looked at, or null to start from the oldest
     */
    public List<Scanned> scan(UUID actorId, PostCleanupPolicy policy, Instant publishedBefore, UUID after, int limit) {
        var keep = new ArrayList<String>();
        if (policy.keepPinned()) {
            keep.add("EXISTS (SELECT 1 FROM status_pin p WHERE p.status_id = s.id AND p.actor_id = s.actor_id)");
        }
        if (policy.keepDirect()) {
            keep.add("s.visibility = 'direct'");
        }
        if (policy.keepPolls()) {
            keep.add("EXISTS (SELECT 1 FROM poll WHERE poll.status_id = s.id)");
        }
        if (policy.keepMedia()) {
            keep.add("EXISTS (SELECT 1 FROM media_attachment m WHERE m.status_id = s.id)");
        }
        if (policy.keepSelfFavourited()) {
            keep.add("EXISTS (SELECT 1 FROM status_like l WHERE l.status_id = s.id AND l.actor_id = s.actor_id)");
        }
        if (policy.keepSelfBookmarked()) {
            keep.add("EXISTS (SELECT 1 FROM status_bookmark b WHERE b.status_id = s.id AND b.actor_id = s.actor_id)");
        }
        // Counted as clients are shown them, so that a post isn't kept for favourites or boosts its author can't see.
        if (policy.minFavourites() != null) {
            keep.add("(SELECT count(*) FROM status_like l JOIN actor a ON a.id = l.actor_id WHERE l.status_id = s.id AND "
                    + LikeRepository.eligible("a") + ") >= :minFavourites");
        }
        if (policy.minBoosts() != null) {
            keep.add("(SELECT count(*) FROM status r JOIN actor a ON a.id = r.actor_id WHERE r.reblog_of_id = s.id AND "
                    + LikeRepository.eligible("a") + ") >= :minBoosts");
        }
        var deletable = keep.isEmpty()
                ? "true"
                : "NOT (s.reblog_of_id IS NULL AND (" + String.join(" OR ", keep) + "))";
        var query = jdbc.sql("SELECT s.id, s.reblog_of_id IS NOT NULL AS boost, " + deletable + " AS deletable "
                        + "FROM status s WHERE s.actor_id = :actorId AND s.id > :after AND s.id < :idBefore "
                        + "AND s.published_at < :before ORDER BY s.id LIMIT :limit")
                .param("actorId", actorId)
                .param("after", after == null ? new UUID(0, 0) : after)
                .param("idBefore", firstIdAt(publishedBefore))
                .param("before", publishedBefore.atOffset(ZoneOffset.UTC))
                .param("limit", limit);
        if (policy.minFavourites() != null) {
            query = query.param("minFavourites", policy.minFavourites());
        }
        if (policy.minBoosts() != null) {
            query = query.param("minBoosts", policy.minBoosts());
        }
        return query.query((rs, row) -> new Scanned(
                rs.getObject("id", UUID.class), rs.getBoolean("boost"), rs.getBoolean("deletable"))).list();
    }

    /**
     * The lowest ID {@link StatusIds} can give a status published at this millisecond.
     */
    private static UUID firstIdAt(Instant instant) {
        return new UUID(instant.toEpochMilli() << 16, 0);
    }

    private static PostCleanupPolicy policy(ResultSet rs) throws SQLException {
        return new PostCleanupPolicy(
                rs.getBoolean("enabled"),
                PostCleanupPolicy.MinimumAge.ofDays(rs.getInt("min_age_days")).orElseThrow(),
                rs.getBoolean("keep_pinned"),
                rs.getBoolean("keep_direct"),
                rs.getBoolean("keep_polls"),
                rs.getBoolean("keep_media"),
                rs.getBoolean("keep_self_favourited"),
                rs.getBoolean("keep_self_bookmarked"),
                rs.getObject("min_favourites", Integer.class),
                rs.getObject("min_boosts", Integer.class));
    }
}
