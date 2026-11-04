package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Stores activities from other servers for processing later, so that an inbox can answer without doing the work.
 */
@Service
public class InboundActivities {

    static final TaskDescriptor<InboundJob> TASK = TaskDescriptor.of("inbound-activity", InboundJob.class);

    private final JdbcClient jdbc;
    private final SchedulerClient scheduler;

    InboundActivities(JdbcClient jdbc, SchedulerClient scheduler) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    /**
     * Queues an activity, unless it's been seen before, since servers can send an activity again. Signatures have to be
     * checked before this, because anything stored here can be written by whoever sends it.
     *
     * @return whether this was new. An inbox should answer the same either way.
     */
    @Transactional
    public boolean accept(InboundActivity activity) {
        var added = jdbc.sql("""
                        INSERT INTO inbound_activity (activity_id, actor_uri, type, body)
                        VALUES (:id, :actorUri, :type, :body)
                        ON CONFLICT (activity_id) DO NOTHING
                        """)
                .param("id", activity.id())
                .param("actorUri", activity.actorUri())
                .param("type", activity.type())
                .param("body", activity.body())
                .update();
        if (added == 0) {
            return false;
        }
        Jobs.schedule(scheduler, TASK, new InboundJob(activity.id(), Instant.now()), activity.id());
        return true;
    }
}
