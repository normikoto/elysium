package dev.biribiri.elysium.content.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Repository
public class ThreadMuteRepository {
    private final JdbcClient jdbc;

    ThreadMuteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID actorId, UUID statusId) {
        jdbc.sql("""
                        INSERT INTO status_thread_mute (actor_id, thread_id)
                        SELECT :actor, thread_id FROM status WHERE id = :status
                        ON CONFLICT DO NOTHING
                        """).param("actor", actorId).param("status", statusId).update();
    }

    public void delete(UUID actorId, UUID statusId) {
        jdbc.sql("""
                        DELETE FROM status_thread_mute m USING status s
                        WHERE s.id = :status AND m.thread_id = s.thread_id AND m.actor_id = :actor
                        """).param("actor", actorId).param("status", statusId).update();
    }

    public boolean isMuted(UUID actorId, UUID statusId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM status s JOIN status_thread_mute m ON m.thread_id = s.thread_id
                        WHERE s.id = :status AND m.actor_id = :actor)
                        """).param("actor", actorId).param("status", statusId).query(Boolean.class).single();
    }

    public Set<UUID> mutedAmong(UUID actorId, Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("""
                        SELECT s.id FROM status s JOIN status_thread_mute m ON m.thread_id = s.thread_id
                        WHERE m.actor_id = :actor AND s.id IN (:ids)
                        """).param("actor", actorId).param("ids", statusIds).query(UUID.class).list());
    }

    /** Called with the ID assignment lock held, as merging threads is, so that a mute can't be left on a merged thread. */
    void move(UUID fromThread, UUID toThread) {
        jdbc.sql("""
                        INSERT INTO status_thread_mute (actor_id, thread_id)
                        SELECT actor_id, :to FROM status_thread_mute WHERE thread_id = :from
                        ON CONFLICT DO NOTHING
                        """).param("from", fromThread).param("to", toThread).update();
        jdbc.sql("DELETE FROM status_thread_mute WHERE thread_id = :from").param("from", fromThread).update();
    }

    public void removeForActor(UUID actorId) {
        jdbc.sql("DELETE FROM status_thread_mute WHERE actor_id = :actor").param("actor", actorId).update();
    }
}
