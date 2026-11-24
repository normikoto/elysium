package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.ActorSql;
import dev.biribiri.elysium.content.AccountDirectory;
import dev.biribiri.elysium.graph.HiddenAccounts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DirectoryRepository {

    private final JdbcClient jdbc;

    DirectoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> find(
            Optional<UUID> viewer, AccountDirectory.Order order, boolean localOnly, int offset, int limit) {
        var sql = new StringBuilder("""
                SELECT a.id FROM profile p
                JOIN actor a ON a.id = p.actor_id
                WHERE p.discoverable AND p.moved_to_uri IS NULL AND\s""")
                .append(ActorSql.active("a"))
                .append(" AND ").append(ActorSql.serverNotSuspended("a"));
        if (localOnly) {
            sql.append(" AND a.is_local");
        }
        if (viewer.isPresent()) {
            sql.append(" AND ").append(HiddenAccounts.visibleInTimelines("a"));
        }
        sql.append(switch (order) {
            case ACTIVE -> " ORDER BY " + StatusRepository.lastStatusAt("a.id") + " DESC NULLS LAST, a.id DESC";
            // Actor IDs are random, unlike Mastodon's, which it orders by.
            case NEW -> " ORDER BY a.created_at DESC, a.id DESC";
        });
        sql.append(" LIMIT :limit OFFSET :offset");
        var query = jdbc.sql(sql.toString())
                .param("limit", limit)
                .param("offset", offset);
        if (viewer.isPresent()) {
            query = query.param("viewer", viewer.get());
        }
        return query.query(UUID.class).list();
    }
}
