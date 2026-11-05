package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Removes a deleted account's statuses and follows, and a suspended remote actor's follows, which would otherwise give it a
 * way to keep receiving statuses. A suspended local account keeps everything, so that its suspension can be undone. This
 * looks at the actor's state now, so it's safe to run again, and does nothing for a suspension that's since ended.
 */
@Configuration(proxyBeanMethods = false)
class AccountCleanupTask {

    private final ActorService actors;
    private final StatusService statuses;
    private final FollowService follows;

    AccountCleanupTask(ActorService actors, StatusService statuses, FollowService follows) {
        this.actors = actors;
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
                statuses.removeAllBy(actor.id());
                follows.removeForActor(actor.id());
            } else if (actor.isSuspended() && !actors.isLocal(actor)) {
                follows.removeForActor(actor.id());
            }
        });
        return Jobs.done();
    }
}
