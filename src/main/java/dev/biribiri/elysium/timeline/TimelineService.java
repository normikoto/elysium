package dev.biribiri.elysium.timeline;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.graph.AccountLists;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TimelineService {

    private final StatusService statuses;
    private final FollowService follows;
    private final AccountLists lists;

    TimelineService(StatusService statuses, FollowService follows, AccountLists lists) {
        this.statuses = statuses;
        this.follows = follows;
        this.lists = lists;
    }

    /**
     * Read from the statuses stored here each time, rather than from a feed kept for each account, so following or
     * unfollowing someone changes it straight away, with nothing to fill or clean up.
     */
    public List<Status> homeTimeline(UUID viewerId, StatusPage page) {
        return statuses.findHome(viewerId, follows.followedIds(viewerId), page);
    }

    /**
     * @return empty if the owner has no such list
     */
    public Optional<List<Status>> listTimeline(UUID ownerId, UUID listId, StatusPage page) {
        return lists.find(ownerId, listId).map(list -> statuses.findList(list, page));
    }

    /**
     * Which of these lists' timelines would list the status, so that a stream shows only what the timeline would. Lists
     * that have since been deleted are left out.
     */
    public Set<UUID> listsShowing(UUID statusId, Collection<UUID> listIds) {
        if (listIds.isEmpty()) return Set.of();
        return Set.copyOf(statuses.listsShowing(statusId, lists.findByIds(listIds)));
    }

    /**
     * @param viewer who's looking, from whom what they've blocked or muted is left out
     */
    public List<Status> publicTimeline(StatusOrigin origin, StatusPage page, boolean onlyMedia, Optional<UUID> viewer) {
        return statuses.findPublic(origin, page, onlyMedia, viewer);
    }

    /**
     * @param viewer who's looking, from whom what they've blocked or muted is left out
     */
    public List<Status> tagTimeline(
            TagFilter tags, StatusOrigin origin, StatusPage page, boolean onlyMedia, Optional<UUID> viewer) {
        return statuses.findTagged(tags, origin, page, onlyMedia, viewer);
    }

    /**
     * Which of these accounts would see the status in their home timeline, so that a stream shows only what the timeline
     * would.
     */
    public List<UUID> homeViewers(UUID statusId, Collection<UUID> candidates) {
        return statuses.homeViewers(statusId, candidates);
    }

    /**
     * Whether {@link #publicTimeline} would list this status.
     */
    public boolean inPublic(UUID statusId, StatusOrigin origin, boolean onlyMedia, Optional<UUID> viewer) {
        return statuses.inPublic(statusId, origin, onlyMedia, viewer);
    }

    /**
     * Whether {@link #tagTimeline} would list this status.
     */
    public boolean inTag(UUID statusId, TagFilter tags, StatusOrigin origin, boolean onlyMedia, Optional<UUID> viewer) {
        return statuses.inTagged(statusId, tags, origin, onlyMedia, viewer);
    }
}
