package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Removes the follows of actors on blocked servers. Actors themselves are kept, because other things can refer to them, and
 * nothing is sent to them. This has to be safe to run again, and it's fine for it to find nothing.
 */
@Configuration(proxyBeanMethods = false)
class DomainBlockCleanupTask {

    private final DomainBlocks blocks;
    private final FollowService follows;

    DomainBlockCleanupTask(DomainBlocks blocks, FollowService follows) {
        this.blocks = blocks;
        this.follows = follows;
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
        blocks.blockedHosts(follows.remoteDomains()).forEach(follows::removeForDomain);
        return Jobs.done();
    }
}
