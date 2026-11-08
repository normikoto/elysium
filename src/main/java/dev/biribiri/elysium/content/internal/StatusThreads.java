package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusThreadsMerged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class StatusThreads {
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final StatusRepository statuses;

    StatusThreads(JdbcClient jdbc, ApplicationEventPublisher events, StatusRepository statuses) {
        this.jdbc = jdbc;
        this.events = events;
        this.statuses = statuses;
    }

    /** Called while the status ID assignment lock is held, so joining threads cannot race another insertion. */
    public void assign(Status status, URI parentUri) {
        // Joining the parent's thread directly keeps an ordinary reply from merging, which every merge's listener pays for.
        var parentThread = parentUri == null ? Optional.<UUID>empty() : threadOf(parentUri);
        alias(status.uri(), parentThread.orElse(status.id()), status.actorId());
        if (parentUri != null) {
            alias(parentUri, threadOf(status.uri()).orElseThrow(), null);
            var pair = jdbc.sql("""
                            SELECT least(a.thread_id, b.thread_id) AS keep, greatest(a.thread_id, b.thread_id) AS remove
                            FROM status_thread_alias a CROSS JOIN status_thread_alias b
                            WHERE a.uri = :uri AND b.uri = :parent
                            """)
                    .param("uri", status.uri().toString()).param("parent", parentUri.toString())
                    .query(Pair.class)
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

    private Optional<UUID> threadOf(URI uri) {
        return jdbc.sql("SELECT thread_id FROM status_thread_alias WHERE uri = :uri")
                .param("uri", uri.toString()).query(UUID.class).optional();
    }

    private void alias(URI uri, UUID threadId, UUID authorId) {
        jdbc.sql("""
                        INSERT INTO status_thread_alias (uri, thread_id, actor_id) VALUES (:uri, :thread, :author)
                        ON CONFLICT (uri) DO UPDATE SET actor_id = coalesce(status_thread_alias.actor_id, EXCLUDED.actor_id)
                        """).param("uri", uri.toString()).param("thread", threadId)
                .param("author", authorId, java.sql.Types.OTHER).update();
    }

    @Transactional
    public void removeForActor(UUID actorId) {
        statuses.lockIdAssignment();
        removeOrphans(jdbc.sql("DELETE FROM status_thread_alias WHERE actor_id = :id RETURNING thread_id")
                .param("id", actorId).query(UUID.class).list());
    }

    @Transactional
    public void removeForDomain(String domain) {
        statuses.lockIdAssignment();
        removeOrphans(jdbc.sql("""
                        DELETE FROM status_thread_alias t USING actor a
                        WHERE t.actor_id = a.id AND NOT a.is_local AND a.domain = :domain RETURNING t.thread_id
                        """).param("domain", domain).query(UUID.class).list());
    }

    /**
     * Callers hold the ID assignment lock from before their first statement, so a reply that has just joined one of these
     * threads is committed and visible here, and its parent's alias isn't taken for an orphan.
     */
    private void removeOrphans(List<UUID> threads) {
        if (threads.isEmpty()) return;
        jdbc.sql("""
                        DELETE FROM status_thread_alias a WHERE a.thread_id = ANY(CAST(:threads AS uuid[]))
                        AND NOT EXISTS (SELECT 1 FROM status s WHERE s.thread_id = a.thread_id)
                        """).param("threads", threads.stream().distinct().map(UUID::toString).toArray(String[]::new)).update();
    }

    private record Pair(UUID keep, UUID remove) {
    }
}
