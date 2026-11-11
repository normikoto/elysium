package dev.biribiri.elysium.content.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class FollowersOnlyRecipientRepository {

    private final JdbcClient jdbc;

    FollowersOnlyRecipientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID statusId, UUID authorId, Collection<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO followers_only_recipient (status_id, author_id, actor_id)
                        SELECT :statusId, :authorId, unnest(CAST(:actorIds AS uuid[]))
                        ON CONFLICT DO NOTHING
                        """)
                .param("statusId", statusId)
                .param("authorId", authorId)
                .param("actorIds", ids(actorIds))
                .update();
    }

    public List<URI> inboxes(UUID statusId, Collection<UUID> others, Collection<UUID> onlyThese) {
        var sql = """
                SELECT DISTINCT COALESCE(a.shared_inbox_uri, a.inbox_uri)
                FROM actor a
                WHERE (a.id IN (SELECT actor_id FROM followers_only_recipient WHERE status_id = :statusId)
                       OR a.id = ANY (CAST(:others AS uuid[])))
                  AND NOT a.is_local AND a.suspended_at IS NULL AND a.deleted_at IS NULL
                """;
        if (onlyThese == null) {
            return jdbc.sql(sql)
                    .param("statusId", statusId)
                    .param("others", ids(others))
                    .query(URI.class)
                    .list();
        }
        return jdbc.sql(sql + " AND a.id = ANY (CAST(:onlyThese AS uuid[]))")
                .param("statusId", statusId)
                .param("others", ids(others))
                .param("onlyThese", ids(onlyThese))
                .query(URI.class)
                .list();
    }

    public boolean wasSent(UUID statusId, UUID actorId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM followers_only_recipient WHERE status_id = :statusId AND actor_id = :actorId
                        )
                        """)
                .param("statusId", statusId)
                .param("actorId", actorId)
                .query(Boolean.class)
                .single();
    }

    public void forget(UUID statusId) {
        jdbc.sql("DELETE FROM followers_only_recipient WHERE status_id = :statusId").param("statusId", statusId).update();
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM followers_only_recipient WHERE author_id = :actorId OR actor_id = :actorId")
                .param("actorId", actorId)
                .update();
    }

    public void removeForDomain(String domain) {
        jdbc.sql("""
                        DELETE FROM followers_only_recipient
                        WHERE actor_id IN (SELECT id FROM actor WHERE domain = :domain AND NOT is_local)
                        """)
                .param("domain", domain)
                .update();
    }

    private static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }
}
