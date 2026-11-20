package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.StatusPage;
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
public class BookmarkRepository {
    private final JdbcClient jdbc;

    BookmarkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(UUID id, UUID statusId) {
    }

    public void insert(UUID actorId, UUID statusId) {
        jdbc.sql("""
                        INSERT INTO status_bookmark (id, actor_id, status_id) VALUES (:id, :actor, :status)
                        ON CONFLICT (actor_id, status_id) DO NOTHING
                        """).param("id", Ids.timeOrdered(Instant.now())).param("actor", actorId).param("status", statusId)
                .update();
    }

    public void delete(UUID actorId, UUID statusId) {
        jdbc.sql("DELETE FROM status_bookmark WHERE actor_id = :actor AND status_id = :status")
                .param("actor", actorId).param("status", statusId).update();
    }

    public Set<UUID> bookmarkedAmong(UUID actorId, Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT status_id FROM status_bookmark WHERE actor_id = :actor AND status_id IN (:ids)")
                .param("actor", actorId).param("ids", statusIds).query(UUID.class).list());
    }

    /**
     * @return the actor's bookmarks, the most recent first, paged by the bookmarks' own IDs
     */
    public List<Row> page(UUID actorId, StatusPage page) {
        var sql = new StringBuilder("SELECT id, status_id FROM status_bookmark WHERE actor_id = :actor");
        var parameters = new HashMap<String, Object>();
        parameters.put("actor", actorId);
        if (page.maxId() != null) {
            sql.append(" AND id < :maxId");
            parameters.put("maxId", page.maxId());
        }
        // Predicates are only added when needed, because PostgreSQL can't determine the type of a null parameter.
        var ascending = page.minId() != null;
        if (ascending) {
            sql.append(" AND id > :minId");
            parameters.put("minId", page.minId());
        } else if (page.sinceId() != null) {
            sql.append(" AND id > :sinceId");
            parameters.put("sinceId", page.sinceId());
        }
        sql.append(ascending ? " ORDER BY id ASC" : " ORDER BY id DESC").append(" LIMIT :limit");
        parameters.put("limit", page.limit());
        var rows = jdbc.sql(sql.toString()).params(parameters).query(Row.class).list();
        return ascending ? rows.reversed() : rows;
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM status_bookmark WHERE actor_id = :actor").param("actor", actorId).update();
    }
}
