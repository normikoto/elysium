package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class InboundActivityTask {

    private final StoredActivities stored;
    private final Map<String, InboundActivityHandler> handlers;
    private final DomainBlocks blocks;
    private final ActorService actors;

    InboundActivityTask(
            StoredActivities stored, List<InboundActivityHandler> handlers, DomainBlocks blocks, ActorService actors) {
        this.actors = actors;
        this.stored = stored;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(InboundActivityHandler::type, Function.identity()));
        this.blocks = blocks;
    }

    @Bean
    Task<InboundJob> inboundActivity() {
        return Jobs.task(InboundActivities.TASK, this::run);
    }

    /**
     * An activity can have been queued before its server was blocked or its actor suspended, so this checks again. This only
     * covers the activity's actor, so a handler has to check any other actor or object that it fetches or stores.
     */
    private CompletionHandler<InboundJob> run(InboundJob job) {
        var activity = stored.find(job.activityId());
        if (activity.isEmpty()) {
            return Jobs.activityGone(job);
        }
        var handler = handlers.get(activity.get().type());
        if (handler != null) {
            process(handler, activity.get());
        }
        return Jobs.done();
    }

    private void process(InboundActivityHandler handler, InboundActivity activity) {
        var uri = URI.create(activity.actorUri());
        if (isRefused(uri)) {
            return;
        }
        try {
            handler.handle(activity);
        } catch (PermanentJobFailure e) {
            // A block or suspension landing during this job makes fetching the actor fail, which isn't a problem for an admin
            // to look at.
            if (!isRefused(uri)) {
                throw e;
            }
        }
    }

    private boolean isRefused(URI actor) {
        return blocks.isBlocked(actor.getHost()) || actors.isSuspended(actor);
    }
}
