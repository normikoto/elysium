package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.graph.FollowEntry;
import dev.biribiri.elysium.graph.FollowPage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class MuteRepository {

    private static final String CURRENT = "(expires_at IS NULL OR expires_at > now())";

    private final JdbcClient jdbc;

    MuteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Mutes the target, or changes the mute if there's one already, which counts as new for when it was made.
     */
    public void save(UUID accountId, UUID targetId, boolean notifications, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO account_mute (id, account_id, target_id, notifications, expires_at)
                        VALUES (:id, :account, :target, :notifications, :expiresAt)
                        ON CONFLICT (account_id, target_id) DO UPDATE
                        SET notifications = EXCLUDED.notifications, expires_at = EXCLUDED.expires_at, created_at = now()
                        """)
                .param("id", UUID.randomUUID())
                .param("account", accountId)
                .param("target", targetId)
                .param("notifications", notifications)
                .param("expiresAt", expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public boolean delete(UUID accountId, UUID targetId) {
        return jdbc.sql("DELETE FROM account_mute WHERE account_id = :account AND target_id = :target")
                .param("account", accountId)
                .param("target", targetId)
                .update() > 0;
    }

    /**
     * @return the account's mutes of these accounts that haven't expired, by the account muted
     */
    public Map<UUID, Mute> findCurrent(UUID accountId, Collection<UUID> targetIds) {
        var found = new HashMap<UUID, Mute>();
        if (targetIds.isEmpty()) {
            return found;
        }
        jdbc.sql("SELECT target_id, notifications, expires_at FROM account_mute WHERE account_id = :account"
                        + " AND target_id IN (:targets) AND " + CURRENT)
                .param("account", accountId)
                .param("targets", targetIds)
                .query(rs -> {
                    var expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                    found.put(rs.getObject("target_id", UUID.class),
                            new Mute(rs.getBoolean("notifications"), expiresAt == null ? null : expiresAt.toInstant()));
                });
        return found;
    }

    /**
     * Lists the accounts an account has muted, newest first, leaving out expired mutes and suspended and deleted accounts.
     * The cursors are mute IDs.
     */
    public List<FollowEntry> findPage(UUID accountId, FollowPage page) {
        return Pages.find(jdbc, "account_mute", " AND (r.expires_at IS NULL OR r.expires_at > now())", accountId, page);
    }

    public void deleteForActor(UUID actorId) {
        jdbc.sql("DELETE FROM account_mute WHERE account_id = :id OR target_id = :id").param("id", actorId).update();
    }

    public int deleteExpired() {
        return jdbc.sql("DELETE FROM account_mute WHERE expires_at <= now()").update();
    }

    /**
     * @param expiresAt when it stops, or null if it doesn't
     */
    public record Mute(boolean notifications, Instant expiresAt) {
    }
}
