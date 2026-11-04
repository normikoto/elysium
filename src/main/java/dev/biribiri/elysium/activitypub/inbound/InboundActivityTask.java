package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class InboundActivityTask {

    private final JdbcClient jdbc;
    private final Map<String, InboundActivityHandler> handlers;

    InboundActivityTask(JdbcClient jdbc, List<InboundActivityHandler> handlers) {
        this.jdbc = jdbc;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(InboundActivityHandler::type, Function.identity()));
    }

    @Bean
    Task<InboundJob> inboundActivity() {
        return Jobs.task(InboundActivities.TASK, (instance, context) -> run(instance.getData()));
    }

    private CompletionHandler<InboundJob> run(InboundJob job) {
        jdbc.sql("SELECT actor_uri, type, body FROM inbound_activity WHERE activity_id = :id")
                .param("id", job.activityId())
                .query((rs, row) -> new InboundActivity(
                        job.activityId(), rs.getString("actor_uri"), rs.getString("type"), rs.getString("body")))
                .optional()
                .ifPresent(activity -> {
                    var handler = handlers.get(activity.type());
                    if (handler != null) {
                        handler.handle(activity);
                    }
                });
        return Jobs.done();
    }
}
