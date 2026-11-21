package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.FollowersOnlyRecipients;
import dev.biribiri.elysium.content.BookmarkService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.PostFilters;
import dev.biribiri.elysium.content.PostingDefaults;
import dev.biribiri.elysium.content.ReactionService;
import dev.biribiri.elysium.content.ScheduledStatusService;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.ThreadMuteService;
import dev.biribiri.elysium.graph.AccountDomainBlockService;
import dev.biribiri.elysium.graph.AccountLists;
import dev.biribiri.elysium.graph.AccountMemos;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.MuteService;
import dev.biribiri.elysium.moderation.Reports;
import dev.biribiri.elysium.notification.NotificationService;
import dev.biribiri.elysium.timeline.Markers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Removes a deleted account's statuses, scheduled statuses, attachments, follows, blocks, mutes, memos, filters, lists, profile, moves, and the reports
 * by and about it, and who its followers-only statuses were sent to, and a suspended remote actor's follows, which would otherwise
 * give it a way to keep receiving statuses. A suspended remote actor's statuses are kept, hidden by every query, so that
 * they come back if the suspension is undone. A suspended local account keeps everything, for the same reason. This looks
 * at the actor's state now, so it's safe to run again, and does nothing for a suspension that's since ended.
 */
@Configuration(proxyBeanMethods = false)
class AccountCleanupTask {

    private final NotificationService notifications;
    private final LikeService likes;
    private final ReactionService reactions;
    private final ActorService actors;
    private final StatusService statuses;
    private final FollowService follows;
    private final BlockService blocks;
    private final MuteService mutes;
    private final AccountDomainBlockService domainBlocks;
    private final ProfileService profiles;
    private final MediaAttachments attachments;
    private final FollowersOnlyRecipients followersOnly;
    private final PostingDefaults postingDefaults;
    private final Reports reports;
    private final PollService polls;
    private final StatusPinService pins;
    private final PostFilters filters;
    private final AccountLists lists;
    private final AccountMigrations migrations;
    private final ScheduledStatusService scheduled;
    private final Markers markers;
    private final BookmarkService bookmarks;
    private final ThreadMuteService threadMutes;
    private final AccountMemos memos;

    AccountCleanupTask(ActorService actors, StatusService statuses, FollowService follows, BlockService blocks,
                       MuteService mutes, AccountDomainBlockService domainBlocks, ProfileService profiles,
                       NotificationService notifications, LikeService likes, ReactionService reactions, MediaAttachments attachments,
                       FollowersOnlyRecipients followersOnly,
                       PostingDefaults postingDefaults, Reports reports, PollService polls, StatusPinService pins,
                       PostFilters filters, AccountLists lists, AccountMigrations migrations,
                       ScheduledStatusService scheduled, Markers markers, BookmarkService bookmarks,
                       ThreadMuteService threadMutes, AccountMemos memos) {
        this.memos = memos;
        this.bookmarks = bookmarks;
        this.threadMutes = threadMutes;
        this.markers = markers;
        this.scheduled = scheduled;
        this.migrations = migrations;
        this.lists = lists;
        this.filters = filters;
        this.pins = pins;
        this.polls = polls;
        this.reports = reports;
        this.followersOnly = followersOnly;
        this.postingDefaults = postingDefaults;
        this.blocks = blocks;
        this.mutes = mutes;
        this.domainBlocks = domainBlocks;
        this.attachments = attachments;
        this.notifications = notifications;
        this.likes = likes;
        this.reactions = reactions;
        this.actors = actors;
        this.profiles = profiles;
        this.statuses = statuses;
        this.follows = follows;
    }

    @Bean
    Task<AccountCleanupJob> cleanUpAfterAccount() {
        return Jobs.task(AccountCleanup.TASK, this::run);
    }

    private CompletionHandler<AccountCleanupJob> run(AccountCleanupJob job) {
        actors.findById(job.actorId()).ifPresent(actor -> {
            if (actor.isDeleted()) {
                likes.removeForActor(actor.id());
                reactions.removeForActor(actor.id());
                pins.removeForActor(actor.id());
                bookmarks.removeForActor(actor.id());
                threadMutes.removeForActor(actor.id());
                polls.removeForActor(actor.id());
                // Before its statuses, so that none is posted after they've been removed.
                scheduled.removeAllBy(actor.id());
                statuses.removeAllBy(actor.id());
                // Uploads it hadn't posted too, which removing its statuses doesn't reach.
                attachments.removeAllBy(actor.id());
                follows.removeForActor(actor.id());
                followersOnly.removeForActor(actor.id());
                blocks.removeForActor(actor.id());
                mutes.removeForActor(actor.id());
                memos.removeForActor(actor.id());
                domainBlocks.removeForAccount(actor.id());
                profiles.removeAll(actor.id());
                postingDefaults.removeFor(actor.id());
                filters.removeForActor(actor.id());
                lists.removeForActor(actor.id());
                notifications.removeForActor(actor.id());
                markers.removeForActor(actor.id());
                reports.removeForActor(actor.id());
                migrations.removeForActor(actor.id());
            } else if (actor.isSuspended() && !actors.isLocal(actor)) {
                follows.removeForActor(actor.id());
            }
        });
        return Jobs.done();
    }
}
