package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.FollowersOnlyRecipients;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.ReactionService;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.moderation.Reports;
import dev.biribiri.elysium.notification.NotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Removes the follows, blocks of local accounts, statuses, profiles, moves, and reports of and by actors on blocked servers, and which
 * followers-only statuses they were sent. Local accounts' blocks, mutes, and notes
 * of them are kept, since they're the accounts' own choices, and the domain block can be lifted. Actors themselves are kept, because other things can refer
 * to them, and nothing is sent to them. This has to be safe to run again, and it's fine for it to find nothing.
 */
@Configuration(proxyBeanMethods = false)
class DomainBlockCleanupTask {

    private final NotificationService notifications;
    private final LikeService likes;
    private final ReactionService reactions;
    private final DomainBlocks blocks;
    private final FollowService follows;
    private final BlockService accountBlocks;
    private final StatusService statuses;
    private final FollowersOnlyRecipients followersOnly;
    private final Reports reports;
    private final PollService polls;
    private final StatusPinService pins;
    private final CustomEmojis emojis;
    private final ProfileService profiles;
    private final AccountMigrations migrations;

    DomainBlockCleanupTask(DomainBlocks blocks, FollowService follows, BlockService accountBlocks, StatusService statuses,
                           NotificationService notifications, LikeService likes, ReactionService reactions,
                           FollowersOnlyRecipients followersOnly,
                           Reports reports, PollService polls, StatusPinService pins, CustomEmojis emojis,
                           ProfileService profiles, AccountMigrations migrations) {
        this.migrations = migrations;
        this.profiles = profiles;
        this.pins = pins;
        this.emojis = emojis;
        this.polls = polls;
        this.followersOnly = followersOnly;
        this.reports = reports;
        this.accountBlocks = accountBlocks;
        this.notifications = notifications;
        this.likes = likes;
        this.reactions = reactions;
        this.blocks = blocks;
        this.follows = follows;
        this.statuses = statuses;
    }

    @Bean
    Task<DomainBlockCleanupJob> cleanUpAfterDomainBlock() {
        return Jobs.task(DomainBlockCleanup.TASK, this::run);
    }

    /**
     * Stops if the block has since been removed, since anyone following from that server is allowed to again. Otherwise this
     * looks at every block rather than only this one, so that matching stays in one place.
     */
    private CompletionHandler<DomainBlockCleanupJob> run(DomainBlockCleanupJob job) {
        if (blocks.find(job.blockId()).isEmpty()) {
            return Jobs.done();
        }
        blocks.blockedHosts(follows.remoteDomains()).forEach(domain -> {
            likes.removeForDomain(domain);
            reactions.removeForDomain(domain);
            pins.removeForDomain(domain);
            polls.removeForDomain(domain);
            follows.removeForDomain(domain);
            followersOnly.removeForDomain(domain);
            accountBlocks.removeForDomain(domain);
            statuses.removeForDomain(domain);
            emojis.removeForDomain(domain);
            profiles.removeForDomain(domain);
            notifications.removeForDomain(domain);
            reports.removeForDomain(domain);
            migrations.removeForDomain(domain);
        });
        return Jobs.done();
    }
}
