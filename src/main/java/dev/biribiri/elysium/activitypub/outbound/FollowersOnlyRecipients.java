package dev.biribiri.elysium.activitypub.outbound;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collection;
import java.util.UUID;
import java.util.List;

/**
 * Which remote followers each local followers-only status was sent to. Follows can't say this once someone has unfollowed,
 * or followed again since, which makes their follow look newer than the status. Without this, those servers would never be
 * sent its {@code Delete}, and would keep a private status that's gone.
 */
@Component
class FollowersOnlyRecipients {

    private final JdbcClient jdbc;

    FollowersOnlyRecipients(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds to who a status was sent to. This can run again for the same status.
     */
    void record(UUID statusId, UUID authorId, Collection<UUID> actorIds) {
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

    /**
     * The inboxes of the active actors that a status was sent to, together with {@code others}, with one inbox for those
     * that share one.
     *
     * @param others   actors to include whether or not they're recorded
     * @param onlyThese if not null, leaves out every actor not among these
     */
    List<URI> inboxes(UUID statusId, Collection<UUID> others, Collection<UUID> onlyThese) {
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

    /**
     * For once a status's {@code Delete} has been queued, when nothing more is sent for it.
     */
    void forget(UUID statusId) {
        jdbc.sql("DELETE FROM followers_only_recipient WHERE status_id = :statusId").param("statusId", statusId).update();
    }

    /**
     * Removes the statuses an actor wrote, and what it was sent.
     */
    void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM followers_only_recipient WHERE author_id = :actorId OR actor_id = :actorId")
                .param("actorId", actorId)
                .update();
    }

    /**
     * Removes what a domain's actors were sent.
     *
     * @param domain a domain as {@code actor.domain} has it
     */
    void removeForDomain(String domain) {
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
