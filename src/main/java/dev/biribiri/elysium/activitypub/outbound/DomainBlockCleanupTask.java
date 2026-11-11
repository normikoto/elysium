package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.content.FollowersOnlyRecipients;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.inbox.InboxService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Removes the follows, blocks of local accounts, and statuses of actors on blocked servers, and which followers-only statuses
 * they were sent. Local accounts' blocks and mutes
 * of them are kept, since they're the accounts' own choices, and the domain block can be lifted. Actors themselves are kept, because other things can refer
 * to them, and nothing is sent to them. This has to be safe to run again, and it's fine for it to find nothing.
 */
@Configuration(proxyBeanMethods = false)
class DomainBlockCleanupTask {

    private final InboxService inbox;
    private final LikeService likes;
    private final DomainBlocks blocks;
    private final FollowService follows;
    private final BlockService accountBlocks;
    private final StatusService statuses;
    private final FollowersOnlyRecipients followersOnly;

    DomainBlockCleanupTask(DomainBlocks blocks, FollowService follows, BlockService accountBlocks, StatusService statuses,
                           InboxService inbox, LikeService likes, FollowersOnlyRecipients followersOnly) {
        this.followersOnly = followersOnly;
        this.accountBlocks = accountBlocks;
        this.inbox = inbox;
        this.likes = likes;
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
            follows.removeForDomain(domain);
            followersOnly.removeForDomain(domain);
            accountBlocks.removeForDomain(domain);
            statuses.removeForDomain(domain);
            inbox.removeForDomain(domain);
        });
        return Jobs.done();
    }
}
