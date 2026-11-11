package dev.biribiri.elysium.graph.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Repository
public class AccountDomainBlockRepository {

    private final JdbcClient jdbc;

    AccountDomainBlockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return whether it was inserted, which it isn't if the account already blocks the domain
     */
    public boolean insert(UUID accountId, String domain) {
        return jdbc.sql("""
                        INSERT INTO account_domain_block (id, account_id, domain) VALUES (:id, :account, :domain)
                        ON CONFLICT (account_id, domain) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("account", accountId)
                .param("domain", domain)
                .update() > 0;
    }

    public boolean delete(UUID accountId, String domain) {
        return jdbc.sql("DELETE FROM account_domain_block WHERE account_id = :account AND domain = :domain")
                .param("account", accountId)
                .param("domain", domain)
                .update() > 0;
    }

    /**
     * The followers of the account, and those asking to follow it, on a server it has blocked.
     */
    public List<Follower> findBlockedFollowers(UUID accountId) {
        return jdbc.sql("""
                        SELECT a.id, a.uri FROM follow f JOIN actor a ON a.id = f.follower_id
                        WHERE f.followed_id = :account AND %s
                        """.formatted(GraphSql.domainBlocked(":account", "a")))
                .param("account", accountId)
                .query((rs, row) -> new Follower(rs.getObject("id", UUID.class), rs.getString("uri")))
                .list();
    }

    /**
     * Lists the domains an account has blocked, newest first.
     *
     * @param maxId only blocks older than this one
     * @param minId only blocks newer than this one, starting from the oldest of them, but still listed newest first
     */
    public List<Entry> findPage(UUID accountId, UUID maxId, UUID sinceId, UUID minId, int limit) {
        var sql = new StringBuilder("SELECT id, domain FROM account_domain_block r WHERE account_id = :account");
        var parameters = new HashMap<String, Object>();
        parameters.put("account", accountId);
        var cursor = " (SELECT created_at, id FROM account_domain_block WHERE id = ";
        if (maxId != null) {
            sql.append(" AND (r.created_at, r.id) <").append(cursor).append(":maxId)");
            parameters.put("maxId", maxId);
        }
        var ascending = minId != null;
        if (ascending) {
            sql.append(" AND (r.created_at, r.id) >").append(cursor).append(":minId)");
            parameters.put("minId", minId);
        } else if (sinceId != null) {
            sql.append(" AND (r.created_at, r.id) >").append(cursor).append(":sinceId)");
            parameters.put("sinceId", sinceId);
        }
        sql.append(ascending ? " ORDER BY r.created_at ASC, r.id ASC" : " ORDER BY r.created_at DESC, r.id DESC")
                .append(" LIMIT :limit");
        parameters.put("limit", limit);
        var found = jdbc.sql(sql.toString())
                .params(parameters)
                .query((rs, row) -> new Entry(rs.getObject("id", UUID.class), rs.getString("domain")))
                .list();
        return ascending ? found.reversed() : found;
    }

    public void deleteForAccount(UUID accountId) {
        jdbc.sql("DELETE FROM account_domain_block WHERE account_id = :id").param("id", accountId).update();
    }

    public record Follower(UUID id, String uri) {
    }

    public record Entry(UUID id, String domain) {
    }
}
