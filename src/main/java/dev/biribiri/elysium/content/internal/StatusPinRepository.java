package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.StatusPin;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.biribiri.elysium.content.internal.ContentRows.offsetOrNull;

@Repository
public class StatusPinRepository {
    private final JdbcClient jdbc;

    StatusPinRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes changes to one actor's pins, so that two pins can't both pass the limit, and a {@code Remove} can't
     * interleave with the {@code Add} it follows.
     */
    public void lockActor(UUID actorId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "pin\n" + actorId).query((rs, row) -> true).single();
    }

    public Optional<StatusPin> find(UUID id) {
        return jdbc.sql("SELECT * FROM status_pin WHERE id = :id").param("id", id).query(StatusPin.class).optional();
    }

    public Optional<StatusPin> findPair(UUID actorId, UUID statusId) {
        return jdbc.sql("SELECT * FROM status_pin WHERE actor_id = :actor AND status_id = :status")
                .param("actor", actorId).param("status", statusId).query(StatusPin.class).optional();
    }

    public int count(UUID actorId) {
        return jdbc.sql("SELECT count(*) FROM status_pin WHERE actor_id = :actor")
                .param("actor", actorId).query(Integer.class).single();
    }

    public StatusPin insert(UUID actorId, UUID statusId, Instant pinnedAt, String activityUri) {
        var pin = new StatusPin(Ids.timeOrdered(Instant.now()), actorId, statusId, pinnedAt, activityUri);
        jdbc.sql("""
                        INSERT INTO status_pin (id, actor_id, status_id, pinned_at, activity_uri)
                        VALUES (:id, :actor, :status, :at, :activity)
                        """).param("id", pin.id()).param("actor", actorId).param("status", statusId)
                .param("at", offsetOrNull(pinnedAt)).param("activity", activityUri).update();
        return pin;
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM status_pin WHERE id = :id").param("id", id).update();
    }

    /**
     * @return the actor's pinned statuses, the most recently pinned first
     */
    public List<UUID> pinnedBy(UUID actorId, int limit) {
        return jdbc.sql("SELECT status_id FROM status_pin WHERE actor_id = :actor ORDER BY pinned_at DESC, id DESC LIMIT :limit")
                .param("actor", actorId).param("limit", limit).query(UUID.class).list();
    }

    public Set<UUID> pinnedAmong(UUID actorId, Collection<UUID> statusIds) {
        if (statusIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT status_id FROM status_pin WHERE actor_id = :actor AND status_id IN (:ids)")
                .param("actor", actorId).param("ids", statusIds).query(UUID.class).list());
    }

    /**
     * @return when each of the actor's statuses that's been unpinned was last unpinned, by URI
     */
    public Map<String, Instant> removedAt(UUID actorId) {
        return jdbc.sql("SELECT status_uri, removed_at FROM removed_pin WHERE actor_id = :actor")
                .param("actor", actorId)
                .query((rs, row) -> Map.entry(rs.getString("status_uri"), rs.getObject("removed_at", OffsetDateTime.class)
                        .toInstant()))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public boolean wasRemoved(UUID actorId, URI statusUri, Instant at) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM removed_pin WHERE actor_id = :actor
                            AND md5(status_uri) = md5(:status) AND status_uri = :status AND removed_at >= :at)
                        """).param("actor", actorId).param("status", statusUri.toString()).param("at", offsetOrNull(at))
                .query(Boolean.class).single();
    }

    /**
     * Records a {@code Remove}, and removes the pin unless it's newer.
     */
    public void remove(UUID actorId, URI statusUri, Instant at) {
        jdbc.sql("""
                        INSERT INTO removed_pin (actor_id, status_uri, removed_at) VALUES (:actor, :status, :at)
                        ON CONFLICT (actor_id, md5(status_uri))
                        DO UPDATE SET removed_at = greatest(removed_pin.removed_at, EXCLUDED.removed_at)
                        """).param("actor", actorId).param("status", statusUri.toString()).param("at", offsetOrNull(at)).update();
        jdbc.sql("""
                        DELETE FROM status_pin p USING status s WHERE p.status_id = s.id AND p.actor_id = :actor
                        AND s.uri = :status AND p.pinned_at <= :at
                        """).param("actor", actorId).param("status", statusUri.toString()).param("at", offsetOrNull(at)).update();
    }

    /**
     * Removes the actor's pins other than these, from before this time.
     */
    public void deleteExcept(UUID actorId, Collection<UUID> kept, Instant before) {
        if (kept.isEmpty()) {
            jdbc.sql("DELETE FROM status_pin WHERE actor_id = :actor AND pinned_at <= :before")
                    .param("actor", actorId).param("before", offsetOrNull(before)).update();
            return;
        }
        jdbc.sql("DELETE FROM status_pin WHERE actor_id = :actor AND pinned_at <= :before AND status_id NOT IN (:kept)")
                .param("actor", actorId).param("before", offsetOrNull(before)).param("kept", kept).update();
    }

    public void removeForActors(Collection<UUID> actors) {
        jdbc.sql("DELETE FROM status_pin WHERE actor_id IN (:actors)").param("actors", actors).update();
        jdbc.sql("DELETE FROM removed_pin WHERE actor_id IN (:actors)").param("actors", actors).update();
    }

    public List<UUID> actorsOn(String domain, UUID after, int limit) {
        return jdbc.sql("SELECT id FROM actor WHERE NOT is_local AND domain = :domain AND id > :after ORDER BY id LIMIT :limit")
                .param("domain", domain).param("after", after).param("limit", limit).query(UUID.class).list();
    }

}
