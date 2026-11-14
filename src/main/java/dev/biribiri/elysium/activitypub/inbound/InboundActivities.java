package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stores activities from other servers for processing later, so that an inbox can answer without doing the work.
 */
@Service
public class InboundActivities {

    static final TaskDescriptor<InboundJob> TASK = TaskDescriptor.of("inbound-activity", InboundJob.class);

    private final JdbcClient jdbc;
    private final SchedulerClient scheduler;
    private final Map<String, InboundActivityHandler> handlers;

    InboundActivities(JdbcClient jdbc, SchedulerClient scheduler, List<InboundActivityHandler> handlers) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(InboundActivityHandler::type, Function.identity()));
        this.handlers.values().stream().filter(handler -> handler.acceptsWithoutId() && !handler.processesRepeats())
                .findAny().ifPresent(handler -> {
                    throw new IllegalStateException(handler.type() + " is accepted without an ID, so its handler has to "
                            + "process repeats");
                });
    }

    /**
     * @see InboundActivityHandler#acceptsWithoutId
     */
    boolean acceptsWithoutId(String type) {
        var handler = handlers.get(type);
        return handler != null && handler.acceptsWithoutId();
    }

    /**
     * Queues an activity, unless it's been seen before, since servers can send an activity again. A handler can choose to
     * process a repeat from the same actor. Signatures have to be checked before this, because anything stored here can be
     * written by whoever sends it.
     *
     * @return whether this was new. An inbox should answer the same either way.
     */
    @Transactional
    public boolean accept(InboundActivity activity) {
        var added = jdbc.sql("""
                        INSERT INTO inbound_activity (activity_id, actor_uri, type, body, first_received_at, received_at)
                        VALUES (:id, :actorUri, :type, :body, :receivedAt, :receivedAt)
                        ON CONFLICT (activity_id) DO NOTHING
                        """)
                .param("id", activity.id())
                .param("actorUri", activity.actorUri())
                .param("type", activity.type())
                .param("body", activity.body())
                .param("receivedAt", activity.receivedAt().atOffset(ZoneOffset.UTC))
                .update();
        if (added == 0) {
            if (shouldProcessRepeat(activity)) {
                jdbc.sql("UPDATE inbound_activity SET received_at = GREATEST(received_at, :receivedAt) WHERE activity_id = :id")
                        .param("id", activity.id())
                        .param("receivedAt", activity.receivedAt().atOffset(ZoneOffset.UTC))
                        .update();
                // Its own job, saved along with the new time. One with the same ID would do nothing while the last job is
                // still running or set aside after failing, and it would carry that job's age.
                Jobs.schedule(
                        scheduler,
                        TASK,
                        new InboundJob(activity.id(), Instant.now()),
                        activity.id(),
                        activity.receivedAt().toString());
            }
            return false;
        }
        schedule(activity);
        return true;
    }

    /**
     * Only the actor that sent an activity can repeat it. Another actor on the same server can use its ID, which the first
     * one stored under an ID keeps.
     */
    private boolean shouldProcessRepeat(InboundActivity activity) {
        var handler = handlers.get(activity.type());
        if (handler == null || !handler.processesRepeats()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM inbound_activity
                            WHERE activity_id = :id AND actor_uri = :actorUri AND type = :type
                        )
                        """)
                .param("id", activity.id())
                .param("actorUri", activity.actorUri())
                .param("type", activity.type())
                .query(Boolean.class)
                .single();
    }

    private void schedule(InboundActivity activity) {
        Jobs.schedule(scheduler, TASK, new InboundJob(activity.id(), Instant.now()), activity.id());
    }
}
