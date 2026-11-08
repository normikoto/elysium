package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusThreadsMerged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.UUID;

@Repository
public class StatusThreads {
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    StatusThreads(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Called while the status ID assignment lock is held, so joining threads cannot race another insertion. */
    public void assign(Status status, URI parentUri) {
        alias(status.uri(), status.id(), status.actorId());
        if (parentUri != null) {
            alias(parentUri, status.id(), null);
            var pair = jdbc.sql("""
                            SELECT least(a.thread_id, b.thread_id) AS keep, greatest(a.thread_id, b.thread_id) AS remove
                            FROM status_thread_alias a CROSS JOIN status_thread_alias b
                            WHERE a.uri = :uri AND b.uri = :parent
                            """)
                    .param("uri", status.uri().toString()).param("parent", parentUri.toString())
                    .query((rs, n) -> new Pair(rs.getObject("keep", UUID.class), rs.getObject("remove", UUID.class)))
                    .single();
            if (!pair.keep().equals(pair.remove())) {
                jdbc.sql("UPDATE status SET thread_id = :keep WHERE thread_id = :remove")
                        .param("keep", pair.keep()).param("remove", pair.remove()).update();
                jdbc.sql("UPDATE status_thread_alias SET thread_id = :keep WHERE thread_id = :remove")
                        .param("keep", pair.keep()).param("remove", pair.remove()).update();
                events.publishEvent(new StatusThreadsMerged(pair.remove(), pair.keep()));
            }
        }
        jdbc.sql("""
                        UPDATE status SET thread_id = (SELECT thread_id FROM status_thread_alias WHERE uri = :uri)
                        WHERE id = :id
                        """).param("uri", status.uri().toString()).param("id", status.id()).update();
    }

    private void alias(URI uri, UUID threadId, UUID authorId) {
        jdbc.sql("""
                        INSERT INTO status_thread_alias (uri, thread_id, actor_id) VALUES (:uri, :thread, :author)
                        ON CONFLICT (uri) DO UPDATE SET actor_id = coalesce(status_thread_alias.actor_id, EXCLUDED.actor_id)
                        """).param("uri", uri.toString()).param("thread", threadId)
                .param("author", authorId, java.sql.Types.OTHER).update();
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM status_thread_alias WHERE actor_id = :id").param("id", actorId).update();
        jdbc.sql("""
                DELETE FROM status_thread_alias a WHERE NOT EXISTS (SELECT 1 FROM status s WHERE s.thread_id = a.thread_id)
                """).update();
    }

    public void removeForDomain(String domain) {
        jdbc.sql("DELETE FROM status_thread_alias t USING actor a WHERE t.actor_id = a.id AND NOT a.is_local AND a.domain = :domain")
                .param("domain", domain).update();
        jdbc.sql("DELETE FROM status_thread_alias t WHERE NOT EXISTS (SELECT 1 FROM status s WHERE s.thread_id = t.thread_id)").update();
    }

    private record Pair(UUID keep, UUID remove) {
    }
}
