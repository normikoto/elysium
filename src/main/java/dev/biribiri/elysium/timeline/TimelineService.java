package dev.biribiri.elysium.timeline;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.graph.AccountLists;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.timeline.internal.TimelineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Chooses which statuses each timeline lists, and loads them from {@link StatusService}, which decides what a status is.
 *
 * <p>A page's IDs and its statuses are read in one snapshot, so a status removed in between can't leave the page short,
 * which would make it look like the last page and stop a client paging.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TimelineService {

    private final TimelineRepository timelines;
    private final StatusService statuses;
    private final FollowService follows;
    private final AccountLists lists;

    TimelineService(TimelineRepository timelines, StatusService statuses, FollowService follows, AccountLists lists) {
        this.timelines = timelines;
        this.statuses = statuses;
        this.follows = follows;
        this.lists = lists;
    }

    /**
     * Lists the viewer's own statuses and visible statuses from followed accounts, including direct mentions. As on
     * Mastodon, a followed account's reply is only listed when it's to its author, to the viewer, or to someone they follow,
     * so that following one person doesn't show their side of every conversation. A reply to a status that isn't stored
     * here has no known parent author, so it's left out too.
     *
     * <p>Read from the statuses stored here each time, rather than from a feed kept for each account, so following or
     * unfollowing someone changes it straight away, with nothing to fill or clean up.
     */
    public List<Status> homeTimeline(UUID viewerId, IdPage page) {
        return load(timelines.findHome(viewerId, follows.followedIds(viewerId), page));
    }

    /**
     * @return empty if the owner has no such list
     */
    public Optional<List<Status>> listTimeline(UUID ownerId, UUID listId, IdPage page) {
        return lists.find(ownerId, listId).map(list -> load(timelines.findList(list, page)));
    }

    /**
     * Which of these lists' timelines would list the status, so that a stream shows only what the timeline would. Lists
     * that have since been deleted are left out.
     */
    public Set<UUID> listsShowing(UUID statusId, Collection<UUID> listIds) {
        if (listIds.isEmpty()) return Set.of();
        return Set.copyOf(timelines.listsShowing(statusId, lists.findByIds(listIds)));
    }

    /**
     * Lists public statuses, leaving out replies to other accounts.
     *
     * @param onlyMedia whether to only list statuses with attachments
     * @param viewer    who's looking, from whom what they've blocked or muted is left out
     */
    public List<Status> publicTimeline(StatusOrigin origin, IdPage page, boolean onlyMedia, Optional<UUID> viewer) {
        return load(timelines.findPublic(origin, page, onlyMedia, viewer.orElse(null)));
    }

    /**
     * Lists public statuses with these hashtags, including replies.
     *
     * @param onlyMedia whether to only list statuses with attachments
     * @param viewer    who's looking, from whom what they've blocked or muted is left out
     */
    public List<Status> tagTimeline(
            TagFilter tags, StatusOrigin origin, IdPage page, boolean onlyMedia, Optional<UUID> viewer) {
        return load(timelines.findTagged(tags, origin, page, onlyMedia, viewer.orElse(null)));
    }

    /**
     * Which of these accounts would see the status in their home timeline, so that a stream shows only what the timeline
     * would.
     */
    public List<UUID> homeViewers(UUID statusId, Collection<UUID> candidates) {
        return timelines.homeViewers(statusId, candidates);
    }

    /**
     * Whether {@link #publicTimeline} would list this status.
     */
    public boolean inPublic(UUID statusId, StatusOrigin origin, boolean onlyMedia, Optional<UUID> viewer) {
        return timelines.inPublic(statusId, origin, onlyMedia, viewer.orElse(null));
    }

    /**
     * Whether {@link #tagTimeline} would list this status.
     */
    public boolean inTag(UUID statusId, TagFilter tags, StatusOrigin origin, boolean onlyMedia, Optional<UUID> viewer) {
        return timelines.inTagged(statusId, tags, origin, onlyMedia, viewer.orElse(null));
    }

    /**
     * Loads a page in its order.
     */
    private List<Status> load(List<UUID> ids) {
        var order = new HashMap<UUID, Integer>();
        for (var i = 0; i < ids.size(); i++) {
            order.put(ids.get(i), i);
        }
        return statuses.findStored(ids).stream().sorted(Comparator.comparing(status -> order.get(status.id()))).toList();
    }
}
