package dev.biribiri.elysium.activitypub.delivery;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Queues activities for sending to other servers, with one job for each inbox so that a failing server can't hold up the
 * others.
 */
@Service
public class ActivityDeliveries {

    static final TaskDescriptor<DeliveryJob> TASK = TaskDescriptor.of("activity-delivery", DeliveryJob.class);

    private final JdbcClient jdbc;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy because the scheduler needs every task, and tasks need handlers, which queue activities here
     */
    ActivityDeliveries(JdbcClient jdbc, @Lazy SchedulerClient scheduler) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    /**
     * Queues an activity, as part of the transaction making the change that caused it, so that one can't be saved without
     * the other. Doing this again for the same activity and inbox has no effect, as long as the first job is still queued,
     * so a listener that runs again after a crash is safe.
     *
     * @param inboxes where to send it. To have followers on a server share one delivery, pass its shared inbox for them.
     * @throws org.springframework.transaction.IllegalTransactionStateException if there's no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboundActivity activity, Collection<URI> inboxes) {
        jdbc.sql("""
                        INSERT INTO outbound_activity (activity_id, actor_id, body)
                        VALUES (:id, :actorId, :body)
                        ON CONFLICT (activity_id) DO NOTHING
                        """)
                .param("id", activity.id())
                .param("actorId", activity.actorId())
                .param("body", activity.body())
                .update();

        var now = Instant.now();
        for (var inbox : new LinkedHashSet<>(inboxes)) {
            var job = new DeliveryJob(activity.id(), inbox.toString(), now);
            Jobs.schedule(scheduler, TASK, job, job.activityId(), job.inbox());
        }
    }

    /**
     * Stops any further sending of an activity, so that a status deleted soon after it was posted isn't then delivered.
     * Its jobs finish without sending the next time they run.
     */
    public void cancel(String activityId) {
        jdbc.sql("DELETE FROM outbound_activity WHERE activity_id = :id").param("id", activityId).update();
    }
}
