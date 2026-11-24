package dev.biribiri.elysium.timeline.internal;

import dev.biribiri.elysium.timeline.Marker;
import dev.biribiri.elysium.timeline.MarkerConflictException;
import dev.biribiri.elysium.timeline.Markers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MarkerRepository {
    private final JdbcClient jdbc;

    MarkerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Marker> find(UUID owner, List<String> timelines) {
        var result = new HashMap<String, Marker>();
        if (timelines.isEmpty()) return result;
        jdbc.sql("SELECT * FROM marker WHERE owner_id = :owner AND timeline IN (:timelines)")
                .param("owner", owner).param("timelines", timelines).query((rs, n) -> {
                    result.put(rs.getString("timeline"), new Marker(rs.getObject("last_read_id", UUID.class),
                            rs.getInt("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
                    return true;
                }).list();
        return result;
    }

    public Map<String, Marker> save(UUID owner, Map<String, UUID> positions) {
        var timelines = positions.keySet().stream().sorted().toList();
        var previous = find(owner, timelines);
        for (var timeline : timelines) {
            int changed;
            if (previous.containsKey(timeline)) {
                changed = jdbc.sql("""
                                UPDATE marker SET last_read_id = :last, version = version + 1, updated_at = clock_timestamp()
                                WHERE owner_id = :owner AND timeline = :timeline AND version = :version
                                """).param("last", positions.get(timeline)).param("owner", owner).param("timeline", timeline)
                        .param("version", previous.get(timeline).version()).update();
            } else {
                changed = jdbc.sql("""
                                INSERT INTO marker (owner_id, timeline, last_read_id) VALUES (:owner, :timeline, :last)
                                ON CONFLICT DO NOTHING
                                """).param("owner", owner).param("timeline", timeline).param("last", positions.get(timeline)).update();
            }
            if (changed == 0) throw new MarkerConflictException();
        }
        return find(owner, timelines);
    }

    /**
     * Locks the marker before reading it, so that where it was is where this moved it from, whatever else saves it at the
     * same time. A marker that another request creates first is waited for, then locked and read, which needs the
     * caller's transaction to be read committed, so that the second read sees it.
     */
    public Markers.Advance advance(UUID owner, String timeline, UUID position) {
        var current = lockedPosition(owner, timeline);
        if (current.isEmpty()) {
            var inserted = jdbc.sql("""
                            INSERT INTO marker (owner_id, timeline, last_read_id) VALUES (:owner, :timeline, :last)
                            ON CONFLICT DO NOTHING
                            """).param("owner", owner).param("timeline", timeline).param("last", position).update() != 0;
            if (inserted) return new Markers.Advance(true, null);
            current = lockedPosition(owner, timeline);
            if (current.isEmpty()) return new Markers.Advance(false, null);
        }
        var moved = jdbc.sql("""
                        UPDATE marker SET last_read_id = :last, version = version + 1, updated_at = clock_timestamp()
                        WHERE owner_id = :owner AND timeline = :timeline AND last_read_id < :last
                        """).param("owner", owner).param("timeline", timeline).param("last", position).update() != 0;
        return new Markers.Advance(moved, moved ? current.get() : null);
    }

    private Optional<UUID> lockedPosition(UUID owner, String timeline) {
        return jdbc.sql("SELECT last_read_id FROM marker WHERE owner_id = :owner AND timeline = :timeline FOR UPDATE")
                .param("owner", owner).param("timeline", timeline).query(UUID.class).optional();
    }

    public void removeForOwner(UUID owner) {
        jdbc.sql("DELETE FROM marker WHERE owner_id = :owner").param("owner", owner).update();
    }
}
