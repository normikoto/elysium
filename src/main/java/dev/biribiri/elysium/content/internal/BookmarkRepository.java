package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.Ids;
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
    public List<Row> page(UUID actorId, IdPage page) {
        var sql = new StringBuilder("SELECT id, status_id FROM status_bookmark WHERE actor_id = :actor");
        var parameters = new HashMap<String, Object>();
        parameters.put("actor", actorId);
        sql.append(page.cursors("id", parameters)).append(page.orderAndLimit("id", parameters));
        return page.newestFirst(jdbc.sql(sql.toString()).params(parameters).query(Row.class).list());
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM status_bookmark WHERE actor_id = :actor").param("actor", actorId).update();
    }
}
