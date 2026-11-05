package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
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

    InboundActivityTask(StoredActivities stored, List<InboundActivityHandler> handlers, DomainBlocks blocks) {
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
     * An activity can have been queued before its server was blocked, so this checks again. This only covers the activity's
     * actor, so a handler has to check any other actor or object that it fetches or stores.
     */
    private CompletionHandler<InboundJob> run(InboundJob job) {
        stored.find(job.activityId()).ifPresent(activity -> {
            var handler = handlers.get(activity.type());
            if (handler != null) {
                process(handler, activity);
            }
        });
        return Jobs.done();
    }

    private void process(InboundActivityHandler handler, InboundActivity activity) {
        var host = URI.create(activity.actorUri()).getHost();
        if (blocks.isBlocked(host)) {
            return;
        }
        try {
            handler.handle(activity);
        } catch (PermanentJobFailure e) {
            // A block landing during this job makes fetching the actor fail, which isn't a problem for an admin to look at.
            if (!blocks.isBlocked(host)) {
                throw e;
            }
        }
    }
}
