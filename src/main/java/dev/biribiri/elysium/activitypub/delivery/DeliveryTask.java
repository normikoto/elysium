package dev.biribiri.elysium.activitypub.delivery;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.activitypub.http.ResponseTooLargeException;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.SignedSender;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends one activity to one inbox. No database transaction is open during this, so a slow server doesn't hold a connection.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryTask {

    private static final Duration BACKOFF_SPREAD = Duration.ofSeconds(10);
    private static final Duration BEHIND_DELAY = Duration.ofSeconds(5);
    private static final Duration SUSPENDED_DELAY = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final ActorService actors;
    private final ActorSigningKeys keys;
    private final SignedSender sender;
    private final HostGate gate;
    private final DomainBlocks blocks;

    DeliveryTask(
            JdbcClient jdbc,
            ActorService actors,
            ActorSigningKeys keys,
            SignedSender sender,
            HostGate gate,
            DomainBlocks blocks) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.keys = keys;
        this.sender = sender;
        this.gate = gate;
        this.blocks = blocks;
    }

    @Bean
    Task<DeliveryJob> activityDelivery() {
        return Jobs.task(ActivityDeliveries.TASK, this::run);
    }

    /**
     * Takes a slot first, because a large fan-out to one host means many runs of jobs that can't send yet, and those shouldn't
     * read the database. The activity is loaded before anything else can fail, so that a cancelled job always finishes rather
     * than being parked, however long it has waited.
     */
    private CompletionHandler<DeliveryJob> run(DeliveryJob job) {
        var inbox = URI.create(job.inbox());
        if (inbox.getHost() == null) {
            if (find(job).isEmpty()) {
                return Jobs.activityGone(job);
            }
            throw new PermanentJobFailure("The inbox URL doesn't have a host");
        }
        var host = inbox.getHost().toLowerCase(Locale.ROOT);

        var now = Instant.now();
        if (!gate.tryAcquire(host)) {
            return Jobs.rescheduleAt(gate.nextTurn(host, now));
        }
        try {
            var activity = find(job);
            if (activity.isEmpty()) {
                return Jobs.activityGone(job);
            }
            if (blocks.isBlocked(host)) {
                // It was queued before the block. This comes before backing off, which could end with it failing.
                return Jobs.done();
            }
            var signer = activity.get().isSignedByInstance()
                    ? Optional.<Actor>empty()
                    : actors.findAnyLocalById(activity.get().actorId());
            var whileSuspended = activity.get().whileSuspended();
            if (whileSuspended != WhileSuspended.SEND && signer.filter(found -> !found.isActive()).isPresent()) {
                // It was queued before a suspension or deletion.
                if (signer.get().isDeleted() || whileSuspended == WhileSuspended.DROP || Jobs.hasOutlivedItsActivity(job)) {
                    return Jobs.done();
                }
                return Jobs.rescheduleAt(now.plus(SUSPENDED_DELAY));
            }
            if (!actors.suspendedInboxes(List.of(inbox)).isEmpty()) {
                return Jobs.done();
            }
            var backingOff = gate.backingOffUntil(host, now);
            if (backingOff.isPresent()) {
                if (job.isOverdue(now)) {
                    throw new PermanentJobFailure("Gave up because " + host + " has been failing for too long");
                }
                return later(backingOff.get());
            }
            if ((job.staysBehind() != null || job.staysBehindPrefix() != null)
                    && isRunningFor(job.staysBehind(), job.staysBehindPrefix(), host)) {
                return Jobs.rescheduleAt(now.plus(BEHIND_DELAY));
            }
            send(activity.get(), signer, inbox, host);
            return Jobs.done();
        } finally {
            gate.release(host);
        }
    }

    /**
     * By host and not by inbox, because a server's followers can have moved to its shared inbox since. A job that's dead stays
     * picked until another node notices, so this can wait for that.
     */
    private boolean isRunningFor(String activityId, String activityIdPrefix, String host) {
        return jdbc.sql("""
                        SELECT task.data ->> 'inbox' FROM scheduled_tasks
                        CROSS JOIN LATERAL (SELECT convert_from(task_data, 'UTF8')::json AS data) task
                        WHERE task_name = :task AND picked
                          AND (task.data ->> 'activityId' = :id
                               OR starts_with(task.data ->> 'activityId', CAST(:prefix AS text)))
                        """)
                .param("task", ActivityDeliveries.TASK.getTaskName())
                .param("id", activityId, Types.VARCHAR)
                .param("prefix", activityIdPrefix, Types.VARCHAR)
                .query(String.class)
                .list()
                .stream()
                .map(inbox -> URI.create(inbox).getHost())
                .anyMatch(host::equalsIgnoreCase);
    }

    private Optional<OutboundActivity> find(DeliveryJob job) {
        return jdbc.sql("SELECT actor_id, body, while_suspended FROM outbound_activity WHERE activity_id = :id")
                .param("id", job.activityId())
                .query((rs, row) -> new OutboundActivity(
                        job.activityId(),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("body"),
                        WhileSuspended.valueOf(rs.getString("while_suspended"))))
                .optional();
    }

    /**
     * @param signer the activity's actor in any state, because an actor has to be able to sign what says that it's been
     *               suspended or deleted
     */
    void send(OutboundActivity activity, Optional<Actor> signer, URI inbox, String host) {
        var key = activity.isSignedByInstance()
                ? keys.forInstance()
                : signer.flatMap(keys::forActor)
                        .orElseThrow(() -> new PermanentJobFailure("The activity's actor no longer has a local key"));
        Response response;
        try {
            response = sender.post(inbox, activity.body().getBytes(StandardCharsets.UTF_8), key);
        } catch (IllegalArgumentException e) {
            throw new PermanentJobFailure("The inbox URL can't be signed", e);
        } catch (BlockedTargetException e) {
            if (blocks.isBlocked(host)) {
                // Blocked since this job's check, so this finishes as it would have then.
                return;
            }
            // This extends IOException, but it's about where the request was going and not about the server's health.
            throw new PermanentJobFailure("The inbox URL isn't allowed", e);
        } catch (ResponseTooLargeException e) {
            // The host answered, and would answer the same way again, having probably received the activity each time.
            gate.succeeded(host);
            throw new PermanentJobFailure("The inbox's answer was too large", e);
        } catch (ConnectionRequestTimeoutException e) {
            // This server's own connections were all busy, which says nothing about the host's health.
            throw new UncheckedIOException(e);
        } catch (IOException e) {
            gate.failed(host, Instant.now(), Duration.ZERO);
            throw new UncheckedIOException(e);
        }

        var status = response.status();
        if (status >= 500 || status == 408 || status == 429) {
            var now = Instant.now();
            gate.failed(host, now, response.retryAfterDelay(now));
            throw new TemporaryDeliveryFailure("The inbox answered with a " + status);
        }
        gate.succeeded(host);
        if (status < 200 || status >= 300) {
            throw new PermanentJobFailure("The inbox answered with a " + status);
        }
    }

    /**
     * Waits for a host's backoff to end. The time is spread out, so that all of the host's jobs don't arrive at once.
     */
    private static CompletionHandler<DeliveryJob> later(Instant time) {
        return Jobs.rescheduleAt(time.plusMillis(ThreadLocalRandom.current().nextLong(BACKOFF_SPREAD.toMillis())));
    }

    private static final class TemporaryDeliveryFailure extends RuntimeException {
        TemporaryDeliveryFailure(String message) {
            super(message);
        }
    }
}
