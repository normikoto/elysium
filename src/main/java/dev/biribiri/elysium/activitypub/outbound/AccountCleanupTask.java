package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.FollowersOnlyRecipients;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.PostingDefaults;
import dev.biribiri.elysium.graph.AccountDomainBlockService;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.MuteService;
import dev.biribiri.elysium.inbox.InboxService;
import dev.biribiri.elysium.moderation.Reports;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Removes a deleted account's statuses, attachments, follows, blocks, mutes, profile, and the reports by and about it, and who its followers-only
 * statuses were sent to, and a suspended remote actor's follows, which would otherwise give it a
 * way to keep receiving statuses. A suspended remote actor's statuses are kept, hidden by every query, so that they come back
 * if the suspension is undone. A suspended local account keeps everything, for the same reason. This
 * looks at the actor's state now, so it's safe to run again, and does nothing for a suspension that's since ended.
 */
@Configuration(proxyBeanMethods = false)
class AccountCleanupTask {

    private final InboxService inbox;
    private final LikeService likes;
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

    AccountCleanupTask(ActorService actors, StatusService statuses, FollowService follows, BlockService blocks,
                       MuteService mutes, AccountDomainBlockService domainBlocks, ProfileService profiles, InboxService inbox,
                       LikeService likes, MediaAttachments attachments, FollowersOnlyRecipients followersOnly,
                       PostingDefaults postingDefaults, Reports reports, PollService polls) {
        this.polls = polls;
        this.reports = reports;
        this.followersOnly = followersOnly;
        this.postingDefaults = postingDefaults;
        this.blocks = blocks;
        this.mutes = mutes;
        this.domainBlocks = domainBlocks;
        this.attachments = attachments;
        this.inbox = inbox;
        this.likes = likes;
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
                polls.removeForActor(actor.id());
                statuses.removeAllBy(actor.id());
                // Uploads it hadn't posted too, which removing its statuses doesn't reach.
                attachments.removeAllBy(actor.id());
                follows.removeForActor(actor.id());
                followersOnly.removeForActor(actor.id());
                blocks.removeForActor(actor.id());
                mutes.removeForActor(actor.id());
                domainBlocks.removeForAccount(actor.id());
                profiles.removeAll(actor.id());
                postingDefaults.removeFor(actor.id());
                inbox.removeForActor(actor.id());
                reports.removeForActor(actor.id());
            } else if (actor.isSuspended() && !actors.isLocal(actor)) {
                follows.removeForActor(actor.id());
            }
        });
        return Jobs.done();
    }
}
