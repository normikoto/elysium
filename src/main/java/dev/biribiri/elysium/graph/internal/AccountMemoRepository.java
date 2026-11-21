package dev.biribiri.elysium.graph.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class AccountMemoRepository {

    private final JdbcClient jdbc;

    AccountMemoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID accountId, UUID targetId, String memo) {
        jdbc.sql("""
                        INSERT INTO account_memo (account_id, target_id, memo) VALUES (:account, :target, :memo)
                        ON CONFLICT (account_id, target_id) DO UPDATE SET memo = EXCLUDED.memo, updated_at = now()
                        """)
                .param("account", accountId)
                .param("target", targetId)
                .param("memo", memo)
                .update();
    }

    public void delete(UUID accountId, UUID targetId) {
        jdbc.sql("DELETE FROM account_memo WHERE account_id = :account AND target_id = :target")
                .param("account", accountId)
                .param("target", targetId)
                .update();
    }

    /**
     * @return the account's memos about these accounts, by the account each is about
     */
    public Map<UUID, String> find(UUID accountId, Collection<UUID> targetIds) {
        var found = new HashMap<UUID, String>();
        if (targetIds.isEmpty()) {
            return found;
        }
        jdbc.sql("SELECT target_id, memo FROM account_memo WHERE account_id = :account AND target_id IN (:targets)")
                .param("account", accountId)
                .param("targets", targetIds)
                .query(rs -> {
                    found.put(rs.getObject("target_id", UUID.class), rs.getString("memo"));
                });
        return found;
    }

    public void deleteForActor(UUID actorId) {
        jdbc.sql("DELETE FROM account_memo WHERE account_id = :id OR target_id = :id").param("id", actorId).update();
    }
}
