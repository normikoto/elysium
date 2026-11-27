package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.TrendsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class Trends {

    public record Sharing(String value, List<TagDay> history) {
    }

    private final TrendsRepository trends;
    private final boolean enabled;

    Trends(TrendsRepository trends, @Value("${elysium.trends.enabled:true}") boolean enabled) {
        this.trends = trends;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Sharing> tags(Optional<UUID> viewer, int limit, int offset) {
        return trends.tags(viewer, limit, offset);
    }

    public List<Sharing> links(Optional<UUID> viewer, int limit, int offset) {
        return trends.links(viewer, limit, offset);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Status> statuses(Optional<UUID> viewer, int limit, int offset) {
        return trends.statuses(viewer, limit, offset);
    }
}
