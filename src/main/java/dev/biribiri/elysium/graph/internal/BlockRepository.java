package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import dev.biribiri.elysium.graph.HiddenAccounts;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class BlockRepository {

    private static final RowMapper<Row> ROW_MAPPER = (rs, rowNum) -> new Row(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getObject("target_id", UUID.class),
            rs.getString("activity_uri"),
            rs.getObject("activity_at", OffsetDateTime.class).toInstant());

    private static final String COLUMNS = "id, account_id, target_id, activity_uri, activity_at";

    private final JdbcClient jdbc;

    BlockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> findByPair(UUID accountId, UUID targetId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account_block WHERE account_id = :account AND target_id = :target")
                .param("account", accountId)
                .param("target", targetId)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<Row> findByActivity(UUID accountId, String activityUri) {
        return jdbc.sql("""
                        SELECT %s FROM account_block
                        WHERE account_id = :account AND md5(activity_uri) = md5(:activity) AND activity_uri = :activity
                        """.formatted(COLUMNS))
                .param("account", accountId)
                .param("activity", activityUri)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * @return whether it was inserted, which it isn't if the account already blocks the target
     */
    public boolean insert(UUID accountId, UUID targetId, String activityUri, Instant activityAt) {
        return jdbc.sql("""
                        INSERT INTO account_block (id, account_id, target_id, activity_uri, activity_at)
                        VALUES (:id, :account, :target, :activity, :activityAt)
                        ON CONFLICT (account_id, target_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("account", accountId)
                .param("target", targetId)
                .param("activity", activityUri)
                .param("activityAt", time(activityAt))
                .update() > 0;
    }

    public void replaceActivity(UUID id, String activityUri, Instant activityAt) {
        jdbc.sql("UPDATE account_block SET activity_uri = :activity, activity_at = :activityAt WHERE id = :id")
                .param("id", id)
                .param("activity", activityUri)
                .param("activityAt", time(activityAt))
                .update();
    }

    public Optional<Row> deletePair(UUID accountId, UUID targetId) {
        return jdbc.sql("DELETE FROM account_block WHERE account_id = :account AND target_id = :target RETURNING " + COLUMNS)
                .param("account", accountId)
                .param("target", targetId)
                .query(ROW_MAPPER)
                .optional();
    }

    public void deleteByActivity(UUID accountId, String activityUri) {
        jdbc.sql("""
                        DELETE FROM account_block
                        WHERE account_id = :account AND md5(activity_uri) = md5(:activity) AND activity_uri = :activity
                        """)
                .param("account", accountId)
                .param("activity", activityUri)
                .update();
    }

    public void deleteByPair(UUID accountId, UUID targetId, Instant arrivedBy) {
        jdbc.sql("""
                        DELETE FROM account_block
                        WHERE account_id = :account AND target_id = :target AND activity_at <= :arrivedBy
                        """)
                .param("account", accountId)
                .param("target", targetId)
                .param("arrivedBy", time(arrivedBy))
                .update();
    }

    public boolean exists(UUID accountId, UUID targetId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account_block WHERE account_id = :account AND target_id = :target)")
                .param("account", accountId)
                .param("target", targetId)
                .query(Boolean.class)
                .single();
    }

    public boolean existsThrough(UUID accountId, UUID targetId, String activityUri) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM account_block
                                       WHERE account_id = :account AND target_id = :target AND activity_uri = :activity)
                        """)
                .param("account", accountId)
                .param("target", targetId)
                .param("activity", activityUri)
                .query(Boolean.class)
                .single();
    }

    /**
     * @return whether the account blocks the other, or the other's server
     */
    public boolean refuses(UUID accountId, UUID otherId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM account_block WHERE account_id = :account AND target_id = :other)
                            OR EXISTS (SELECT 1 FROM actor o WHERE o.id = :other AND %s)
                        """.formatted(GraphSql.domainBlocked(":account", "o")))
                .param("account", accountId)
                .param("other", otherId)
                .query(Boolean.class)
                .single();
    }

    /**
     * @return those of the accounts that block the viewer or the viewer's server
     */
    public Set<UUID> refusing(UUID viewerId, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT o.id FROM actor o, actor v
                        WHERE o.id IN (:accounts) AND v.id = :viewer
                          AND (EXISTS (SELECT 1 FROM account_block b WHERE b.account_id = o.id AND b.target_id = v.id)
                               OR %s)
                        """.formatted(GraphSql.domainBlocked("o.id", "v")))
                .param("viewer", viewerId)
                .param("accounts", accountIds)
                .query(UUID.class)
                .list());
    }

    /**
     * @return those of the accounts that block the sender or the sender's server
     */
    public Set<UUID> refusingSender(Collection<UUID> accountIds, URI senderUri) {
        if (accountIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT o.id FROM actor o, actor v
                        WHERE o.id IN (:accounts) AND v.uri = :sender
                          AND (EXISTS (SELECT 1 FROM account_block b WHERE b.account_id = o.id AND b.target_id = v.id)
                               OR %s)
                        """.formatted(GraphSql.domainBlocked("o.id", "v")))
                .param("sender", senderUri.toString())
                .param("accounts", accountIds)
                .query(UUID.class)
                .list());
    }

    /**
     * The sender has to be stored, or nobody counts.
     *
     * @return whether the actor is local and doesn't refuse the sender, or is remote and has an active local follower that
     * doesn't
     */
    public boolean isAcceptedThrough(URI actorUri, URI senderUri) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM actor p, actor v
                            WHERE p.uri = :actor AND v.uri = :sender
                              AND CASE WHEN p.is_local THEN %s
                                       ELSE EXISTS (
                                           SELECT 1 FROM follow f JOIN actor o ON o.id = f.follower_id
                                           WHERE f.followed_id = p.id AND f.state = 'accepted' AND o.is_local
                                             AND o.suspended_at IS NULL AND o.deleted_at IS NULL AND %s)
                                  END
                        )
                        """.formatted(accepts("p.id"), accepts("o.id")))
                .param("actor", actorUri.toString())
                .param("sender", senderUri.toString())
                .query(Boolean.class)
                .single();
    }

    /**
     * Whether an account doesn't block the joined actor {@code v} or its server.
     */
    private static String accepts(String account) {
        return "NOT EXISTS (SELECT 1 FROM account_block b WHERE b.account_id = " + account + " AND b.target_id = v.id)"
                + " AND NOT " + GraphSql.domainBlocked(account, "v");
    }

    /**
     * @return the blocks between the viewer and any of these accounts, in either direction
     */
    public List<Row> findPairsWith(UUID viewerId, Collection<UUID> otherIds) {
        if (otherIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT %s FROM account_block
                        WHERE (account_id = :viewer AND target_id IN (:others))
                           OR (target_id = :viewer AND account_id IN (:others))
                        """.formatted(COLUMNS))
                .param("viewer", viewerId)
                .param("others", otherIds)
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * @return those of the accounts whose servers the viewer has blocked
     */
    public Set<UUID> domainBlockedAmong(UUID viewerId, Collection<UUID> otherIds) {
        if (otherIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT o.id FROM actor o WHERE o.id IN (:others) AND " + GraphSql.domainBlocked(":viewer", "o"))
                .param("viewer", viewerId)
                .param("others", otherIds)
                .query(UUID.class)
                .list());
    }

    /**
     * @return those of the accounts that the viewer shouldn't see in timelines or threads, as
     * {@link HiddenAccounts#visibleInTimelines} decides
     */
    public Set<UUID> hiddenAmong(UUID viewerId, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT a.id FROM actor a WHERE a.id IN (:accounts) AND NOT "
                        + HiddenAccounts.visibleInTimelines("a"))
                .param("viewer", viewerId)
                .param("accounts", accountIds)
                .query(UUID.class)
                .list());
    }

    /**
     * @param reason the SQL for why an actor joined as {@code a} is hidden
     * @return each account that's hidden, and why
     */
    public Map<UUID, String> hidingReasons(UUID viewerId, Collection<UUID> accountIds, String reason) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        var reasons = new HashMap<UUID, String>();
        jdbc.sql("SELECT a.id, " + reason + " AS reason FROM actor a WHERE a.id IN (:accounts)")
                .param("viewer", viewerId)
                .param("accounts", accountIds)
                .query((rs, row) -> {
                    if (rs.getString("reason") != null) {
                        reasons.put(rs.getObject("id", UUID.class), rs.getString("reason"));
                    }
                    return true;
                })
                .list();
        return reasons;
    }

    /**
     * Lists the accounts an account blocks, newest first, leaving out suspended and deleted ones. The cursors are block IDs.
     */
    public List<FollowEntry> findPage(UUID accountId, FollowPage page) {
        return Pages.find(jdbc, "account_block", "", accountId, page);
    }

    public void deleteForActor(UUID actorId) {
        jdbc.sql("DELETE FROM account_block WHERE account_id = :id OR target_id = :id").param("id", actorId).update();
    }

    /**
     * Removes the blocks that these actors made, which are of local accounts, since only local accounts block remote ones.
     */
    public void deleteMadeBy(Collection<UUID> actorIds) {
        jdbc.sql("DELETE FROM account_block WHERE account_id IN (:ids)").param("ids", actorIds).update();
    }

    public boolean isUndone(String activityUri, String actorUri) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM undone_block
                            WHERE md5(activity_uri) = md5(:activity) AND md5(actor_uri) = md5(:actor)
                              AND activity_uri = :activity AND actor_uri = :actor
                        )
                        """)
                .param("activity", activityUri)
                .param("actor", actorUri)
                .query(Boolean.class)
                .single();
    }

    public void markUndone(String activityUri, String actorUri, Instant undoneAt) {
        jdbc.sql("""
                        INSERT INTO undone_block (activity_uri, actor_uri, undone_at)
                        VALUES (:activity, :actor, :undoneAt)
                        ON CONFLICT (md5(activity_uri), md5(actor_uri))
                        DO UPDATE SET undone_at = GREATEST(undone_block.undone_at, EXCLUDED.undone_at)
                        """)
                .param("activity", activityUri)
                .param("actor", actorUri)
                .param("undoneAt", time(undoneAt))
                .update();
    }

    /**
     * @return whether an {@code Undo} covers a block of the target by the actor that arrived at this time
     */
    public boolean isPairUndone(String actorUri, UUID targetId, Instant activityAt) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM undone_block_pair
                            WHERE md5(actor_uri) = md5(:actor) AND target_id = :target
                              AND actor_uri = :actor AND undone_at >= :activityAt
                        )
                        """)
                .param("actor", actorUri)
                .param("target", targetId)
                .param("activityAt", time(activityAt))
                .query(Boolean.class)
                .single();
    }

    public void markPairUndone(String actorUri, UUID targetId, Instant undoneAt) {
        jdbc.sql("""
                        INSERT INTO undone_block_pair (actor_uri, target_id, undone_at)
                        VALUES (:actor, :target, :undoneAt)
                        ON CONFLICT (md5(actor_uri), target_id)
                        DO UPDATE SET undone_at = GREATEST(undone_block_pair.undone_at, EXCLUDED.undone_at)
                        """)
                .param("actor", actorUri)
                .param("target", targetId)
                .param("undoneAt", time(undoneAt))
                .update();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * @param accountId  who blocks
     * @param activityAt when the activity behind it first arrived
     */
    public record Row(UUID id, UUID accountId, UUID targetId, String activityUri, Instant activityAt) {
    }
}
