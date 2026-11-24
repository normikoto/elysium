package dev.biribiri.elysium.graph.internal;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.graph.FollowedTag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class TagFollowRepository {

    private final JdbcClient jdbc;

    TagFollowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID accountId, String name) {
        jdbc.sql("""
                        INSERT INTO tag_follow (id, account_id, name) VALUES (:id, :account, :name)
                        ON CONFLICT (account_id, name) DO NOTHING
                        """)
                .param("id", Ids.timeOrdered(Instant.now()))
                .param("account", accountId)
                .param("name", name)
                .update();
    }

    public void delete(UUID accountId, String name) {
        jdbc.sql("DELETE FROM tag_follow WHERE account_id = :account AND name = :name")
                .param("account", accountId)
                .param("name", name)
                .update();
    }

    public Set<String> find(UUID accountId, Collection<String> names) {
        if (names.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT name FROM tag_follow WHERE account_id = :account AND name IN (:names)")
                .param("account", accountId)
                .param("names", names)
                .query(String.class)
                .list());
    }

    public List<FollowedTag> findPage(UUID accountId, IdPage page) {
        var parameters = new HashMap<String, Object>();
        parameters.put("account", accountId);
        var sql = "SELECT id, name FROM tag_follow WHERE account_id = :account" + page.cursors("id", parameters)
                + page.orderAndLimit("id", parameters);
        return page.newestFirst(jdbc.sql(sql)
                .params(parameters)
                .query((rs, row) -> new FollowedTag(rs.getObject("id", UUID.class), rs.getString("name")))
                .list());
    }

    public void deleteForAccount(UUID accountId) {
        jdbc.sql("DELETE FROM tag_follow WHERE account_id = :account").param("account", accountId).update();
    }
}
