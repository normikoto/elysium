package dev.biribiri.elysium.timeline;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TimelineService {

    private final StatusService statuses;
    private final FollowService follows;

    TimelineService(StatusService statuses, FollowService follows) {
        this.statuses = statuses;
        this.follows = follows;
    }

    /**
     * Read from the statuses stored here each time, rather than from a feed kept for each account, so following or
     * unfollowing someone changes it straight away, with nothing to fill or clean up.
     */
    public List<Status> homeTimeline(UUID viewerId, StatusPage page) {
        return statuses.findHome(viewerId, follows.followedIds(viewerId), page);
    }

    public List<Status> publicTimeline(StatusOrigin origin, StatusPage page) {
        return statuses.findPublic(origin, page);
    }

    public List<Status> tagTimeline(TagFilter tags, StatusOrigin origin, StatusPage page) {
        return statuses.findTagged(tags, origin, page);
    }
}
