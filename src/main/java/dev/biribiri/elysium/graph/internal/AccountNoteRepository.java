package dev.biribiri.elysium.graph.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class AccountNoteRepository {

    private final JdbcClient jdbc;

    AccountNoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID accountId, UUID targetId, String note) {
        jdbc.sql("""
                        INSERT INTO account_note (account_id, target_id, note) VALUES (:account, :target, :note)
                        ON CONFLICT (account_id, target_id) DO UPDATE SET note = EXCLUDED.note, updated_at = now()
                        """)
                .param("account", accountId)
                .param("target", targetId)
                .param("note", note)
                .update();
    }

    public void delete(UUID accountId, UUID targetId) {
        jdbc.sql("DELETE FROM account_note WHERE account_id = :account AND target_id = :target")
                .param("account", accountId)
                .param("target", targetId)
                .update();
    }

    /**
     * @return the account's notes about these accounts, by the account each is about
     */
    public Map<UUID, String> find(UUID accountId, Collection<UUID> targetIds) {
        var found = new HashMap<UUID, String>();
        if (targetIds.isEmpty()) {
            return found;
        }
        jdbc.sql("SELECT target_id, note FROM account_note WHERE account_id = :account AND target_id IN (:targets)")
                .param("account", accountId)
                .param("targets", targetIds)
                .query(rs -> {
                    found.put(rs.getObject("target_id", UUID.class), rs.getString("note"));
                });
        return found;
    }

    public void deleteForActor(UUID actorId) {
        jdbc.sql("DELETE FROM account_note WHERE account_id = :id OR target_id = :id").param("id", actorId).update();
    }
}
