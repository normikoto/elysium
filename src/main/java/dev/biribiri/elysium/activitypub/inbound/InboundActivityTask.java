package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class InboundActivityTask {

    private final StoredActivities stored;
    private final Map<String, InboundActivityHandler> handlers;

    InboundActivityTask(StoredActivities stored, List<InboundActivityHandler> handlers) {
        this.stored = stored;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(InboundActivityHandler::type, Function.identity()));
    }

    @Bean
    Task<InboundJob> inboundActivity() {
        return Jobs.task(InboundActivities.TASK, this::run);
    }

    private CompletionHandler<InboundJob> run(InboundJob job) {
        var activity = stored.find(job.activityId());
        if (activity.isEmpty()) {
            return Jobs.done();
        }
        var handler = handlers.get(activity.get().type());
        if (handler == null) {
            return Jobs.done();
        }
        handler.handle(activity.get());

        // A repeat that arrived during this can't queue another job while this one exists, and it wasn't what this just read.
        var repeated = stored.find(job.activityId())
                .filter(latest -> latest.receivedAt().isAfter(activity.get().receivedAt()))
                .isPresent();
        return repeated ? (complete, operations) -> operations.reschedule(complete, Instant.now()) : Jobs.done();
    }
}
