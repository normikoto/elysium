package dev.biribiri.elysium.activitypub.delivery;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Queues activities for sending to other servers, with one job for each inbox so that a failing server can't hold up the
 * others.
 */
@Service
public class ActivityDeliveries {

    static final TaskDescriptor<DeliveryJob> TASK = TaskDescriptor.of("activity-delivery", DeliveryJob.class);

    private final JdbcClient jdbc;
    private final SchedulerClient scheduler;
    private final DomainBlocks blocks;
    private final ActorService actors;

    /**
     * @param scheduler lazy because the scheduler needs every task, and tasks need handlers, which queue activities here
     */
    ActivityDeliveries(JdbcClient jdbc, @Lazy SchedulerClient scheduler, DomainBlocks blocks, ActorService actors) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.blocks = blocks;
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
        queue(activity, inboxes, null);
    }

    /**
     * Queues an activity to be sent again, as a new try for each inbox, for when something has asked for it again. A job from
     * an earlier try may have given up and been set aside, and queuing the same job wouldn't replace it. Doing this again
     * for the same try has no effect, so a listener that runs again after a crash is safe. Otherwise this is like
     * {@link #enqueue(OutboundActivity, Collection)}.
     *
     * @param attempt what identifies this try
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboundActivity activity, Collection<URI> inboxes, String attempt) {
        queue(activity, inboxes, null, attempt);
    }

    /**
     * Queues an activity that shouldn't overtake one that's been {@linkplain #cancel cancelled}, as a {@code Delete} shouldn't
     * overtake its {@code Create}. Cancelling can't stop a request in progress, so each job waits while one of the cancelled
     * activity's jobs is running for its host. Otherwise this is like {@link #enqueue(OutboundActivity, Collection)}.
     *
     * <p>Cancel in the same transaction. A job that isn't running by the time these can see the cancel can't send anything.
     *
     * @param cancelledActivityId the activity to stay behind
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAfter(OutboundActivity activity, Collection<URI> inboxes, String cancelledActivityId) {
        queue(activity, inboxes, cancelledActivityId);
    }

    private void queue(OutboundActivity activity, Collection<URI> inboxes, String staysBehind, String... extraIdParts) {
        var allowed = allowed(inboxes);
        if (allowed.isEmpty()) {
            return;
        }
        store(activity);
        var now = Instant.now();
        for (var inbox : allowed) {
            var job = new DeliveryJob(activity.id(), inbox.toString(), now, staysBehind);
            var idParts = Stream.concat(Stream.of(job.activityId(), job.inbox()), Stream.of(extraIdParts))
                    .toArray(String[]::new);
            Jobs.schedule(scheduler, TASK, job, idParts);
        }
    }

    /**
     * Leaves out inboxes on blocked servers, and those of suspended actors. One with no host stays in, so that its job fails where an admin can see it.
     */
    private Set<URI> allowed(Collection<URI> inboxes) {
        var blocked = blocks.blockedHosts(inboxes.stream().map(URI::getHost).toList());
        var allowed = new LinkedHashSet<>(inboxes);
        allowed.removeIf(inbox -> inbox.getHost() != null && blocked.contains(inbox.getHost()));
        allowed.removeAll(actors.suspendedInboxes(allowed));
        return allowed;
    }

    private void store(OutboundActivity activity) {
        jdbc.sql("""
                        INSERT INTO outbound_activity (activity_id, actor_id, body, while_suspended)
                        VALUES (:id, :actorId, :body, :whileSuspended)
                        ON CONFLICT (activity_id) DO UPDATE SET created_at = now()
                        """)
                .param("id", activity.id())
                .param("actorId", activity.actorId())
                .param("body", activity.body())
                .param("whileSuspended", activity.whileSuspended().name())
                .update();
    }

    /**
     * Stops any further sending of an activity, so that a status deleted soon after it was posted isn't then delivered.
     * Its jobs finish without sending the next time they run and get a slot for their host.
     */
    public void cancel(String activityId) {
        jdbc.sql("DELETE FROM outbound_activity WHERE activity_id = :id").param("id", activityId).update();
    }

    /**
     * Stops any further sending of every activity whose ID starts with this, such as each edit's {@code Update} of a status
     * that's been deleted.
     */
    public void cancelStartingWith(String activityIdPrefix) {
        jdbc.sql("DELETE FROM outbound_activity WHERE starts_with(activity_id, :prefix)")
                .param("prefix", activityIdPrefix)
                .update();
    }

    /**
     * Changes what an activity says, for the deliveries that haven't been sent yet, which read it when they send. A
     * status's {@code Create} then carries its latest edit, since a server that doesn't have the status yet can ignore the
     * {@code Update}, as Mastodon does. Does nothing if the activity isn't queued any more.
     */
    public void replaceBody(String activityId, String body) {
        jdbc.sql("UPDATE outbound_activity SET body = :body WHERE activity_id = :id")
                .param("id", activityId)
                .param("body", body)
                .update();
    }

    /**
     * Stops any further sending of an actor's activities, and removes what they said, for when the actor has been deleted.
     *
     * @param keptId the activity that says so, because a listener that runs again mustn't cancel its own
     */
    public void cancelAllBy(UUID actorId, String keptId) {
        jdbc.sql("DELETE FROM outbound_activity WHERE actor_id = :actorId AND activity_id <> :keptId")
                .param("actorId", actorId)
                .param("keptId", keptId)
                .update();
    }
}
