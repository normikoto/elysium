package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.graph.FollowEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class AccountEndorsementRepository {

    private final JdbcClient jdbc;

    AccountEndorsementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return whether the account now endorses the target, which it can only do while it follows it
     */
    public boolean save(UUID accountId, UUID targetId) {
        jdbc.sql("""
                        INSERT INTO account_endorsement (id, account_id, target_id)
                        SELECT :id, follower_id, followed_id FROM follow
                        WHERE follower_id = :account AND followed_id = :target AND state = 'accepted'
                        ON CONFLICT (account_id, target_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("account", accountId)
                .param("target", targetId)
                .update();
        return !find(accountId, List.of(targetId)).isEmpty();
    }

    public void delete(UUID accountId, UUID targetId) {
        jdbc.sql("DELETE FROM account_endorsement WHERE account_id = :account AND target_id = :target")
                .param("account", accountId)
                .param("target", targetId)
                .update();
    }

    /**
     * @return which of these accounts the account endorses
     */
    public Set<UUID> find(UUID accountId, Collection<UUID> targetIds) {
        if (targetIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT target_id FROM account_endorsement WHERE account_id = :account AND target_id IN (:targets)
                        """)
                .param("account", accountId)
                .param("targets", targetIds)
                .query(UUID.class)
                .list());
    }

    /**
     * Lists the accounts an account endorses, newest first, leaving out suspended and deleted ones. The cursors are
     * endorsement IDs.
     */
    public List<FollowEntry> findPage(UUID accountId, IdPage page) {
        return Pages.find(jdbc, "account_endorsement", "", accountId, page);
    }
}
