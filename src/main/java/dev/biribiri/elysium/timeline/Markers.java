package dev.biribiri.elysium.timeline;

import dev.biribiri.elysium.timeline.internal.MarkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class Markers {
    private final MarkerRepository markers;

    Markers(MarkerRepository markers) {
        this.markers = markers;
    }

    public Map<String, Marker> find(UUID owner, List<String> timelines) {
        return markers.find(owner, timelines);
    }

    /**
     * @throws MarkerConflictException if another request changed one of these markers first
     */
    @Transactional
    public Map<String, Marker> save(UUID owner, Map<String, UUID> positions) {
        return markers.save(owner, positions);
    }

    @Transactional
    public void removeForActor(UUID owner) {
        markers.removeForOwner(owner);
    }

    public Optional<UUID> lastRead(UUID owner, String timeline) {
        return Optional.ofNullable(markers.find(owner, List.of(timeline)).get(timeline)).map(Marker::lastReadId);
    }
}
