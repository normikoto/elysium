package dev.biribiri.elysium.timeline;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TimelineService {

    private final StatusService statuses;

    TimelineService(StatusService statuses) {
        this.statuses = statuses;
    }

    public List<Status> publicTimeline(StatusOrigin origin, StatusPage page) {
        return statuses.findPublic(origin, page);
    }
}
