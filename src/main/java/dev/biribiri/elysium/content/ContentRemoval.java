package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorDataRemoval;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class ContentRemoval implements ActorDataRemoval {

    private final LikeService likes;
    private final ReactionService reactions;
    private final StatusPinService pins;
    private final BookmarkService bookmarks;
    private final ThreadMuteService threadMutes;
    private final PollService polls;
    private final ScheduledStatusService scheduled;
    private final StatusService statuses;
    private final MediaAttachments attachments;
    private final FollowersOnlyRecipients followersOnly;
    private final PostingDefaults postingDefaults;
    private final PostFilters filters;
    private final CustomEmojis emojis;

    ContentRemoval(LikeService likes, ReactionService reactions, StatusPinService pins, BookmarkService bookmarks,
                   ThreadMuteService threadMutes, PollService polls, ScheduledStatusService scheduled,
                   StatusService statuses, MediaAttachments attachments, FollowersOnlyRecipients followersOnly,
                   PostingDefaults postingDefaults, PostFilters filters, CustomEmojis emojis) {
        this.likes = likes;
        this.reactions = reactions;
        this.pins = pins;
        this.bookmarks = bookmarks;
        this.threadMutes = threadMutes;
        this.polls = polls;
        this.scheduled = scheduled;
        this.statuses = statuses;
        this.attachments = attachments;
        this.followersOnly = followersOnly;
        this.postingDefaults = postingDefaults;
        this.filters = filters;
        this.emojis = emojis;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        likes.removeForActor(actorId);
        reactions.removeForActor(actorId);
        pins.removeForActor(actorId);
        bookmarks.removeForActor(actorId);
        threadMutes.removeForActor(actorId);
        polls.removeForActor(actorId);
        // Before its statuses, so that none is posted after they've been removed.
        scheduled.removeAllBy(actorId);
        statuses.removeAllBy(actorId);
        // Uploads it hadn't posted too, which removing its statuses doesn't reach.
        attachments.removeAllBy(actorId);
        followersOnly.removeForActor(actorId);
        postingDefaults.removeFor(actorId);
        filters.removeForActor(actorId);
    }

    @Override
    public void removeForDomain(String domain) {
        likes.removeForDomain(domain);
        reactions.removeForDomain(domain);
        pins.removeForDomain(domain);
        polls.removeForDomain(domain);
        followersOnly.removeForDomain(domain);
        statuses.removeForDomain(domain);
        emojis.removeForDomain(domain);
    }
}
