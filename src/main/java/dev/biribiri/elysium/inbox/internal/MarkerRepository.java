package dev.biribiri.elysium.inbox.internal;

import dev.biribiri.elysium.inbox.Marker;
import dev.biribiri.elysium.inbox.MarkerConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        jdbc.sql("SELECT * FROM inbox_marker WHERE owner_id = :owner AND timeline IN (:timelines)")
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
                                UPDATE inbox_marker SET last_read_id = :last, version = version + 1, updated_at = clock_timestamp()
                                WHERE owner_id = :owner AND timeline = :timeline AND version = :version
                                """).param("last", positions.get(timeline)).param("owner", owner).param("timeline", timeline)
                        .param("version", previous.get(timeline).version()).update();
            } else {
                changed = jdbc.sql("""
                                INSERT INTO inbox_marker (owner_id, timeline, last_read_id) VALUES (:owner, :timeline, :last)
                                ON CONFLICT DO NOTHING
                                """).param("owner", owner).param("timeline", timeline).param("last", positions.get(timeline)).update();
            }
            if (changed == 0) throw new MarkerConflictException();
        }
        return find(owner, timelines);
    }

    public UUID notificationPosition(UUID owner) {
        var marker = find(owner, List.of("notifications")).get("notifications");
        return marker == null ? null : marker.lastReadId();
    }
}
