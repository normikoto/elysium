package dev.biribiri.elysium.activitypub.delivery;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.activitypub.http.ResponseTooLargeException;
import dev.biribiri.elysium.activitypub.jobs.ActivityJobs;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.SignedSender;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.moderation.DomainBlocks;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.TimeUnit;

/**
 * Sends one activity to one inbox. No database transaction is open during this, so a slow server doesn't hold a connection.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryTask {

    private static final Duration BACKOFF_SPREAD = Duration.ofSeconds(10);
    private static final Duration BEHIND_DELAY = Duration.ofSeconds(5);
    private static final Duration SUSPENDED_DELAY = Duration.ofMinutes(10);

    /**
     * How long a run can still be going after it was picked. A run sends one request, which can't outlast the client's
     * deadline, and no heartbeat is written during a run that short, so a picked job older than this belongs to a node that
     * died. db-scheduler only notices that after several missed heartbeats, which takes half an hour.
     */
    private static final Duration RUN_LASTS = FederationHttpClient.DEADLINE.plus(Duration.ofMinutes(2));

    private final JdbcClient jdbc;
    private final ActorService actors;
    private final ActorSigningKeys keys;
    private final SignedSender sender;
    private final HostGate gate;
    private final DomainBlocks blocks;
    private final MeterRegistry meters;

    DeliveryTask(
            JdbcClient jdbc,
            ActorService actors,
            ActorSigningKeys keys,
            SignedSender sender,
            HostGate gate,
            DomainBlocks blocks,
            MeterRegistry meters) {
        this.meters = meters;
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
     * read the database, until they've waited long enough to give up. The activity is loaded before anything else can fail, so
     * that a cancelled job always finishes rather than being parked, however long it has waited.
     */
    private CompletionHandler<DeliveryJob> run(DeliveryJob job) {
        var inbox = URI.create(job.inbox());
        if (inbox.getHost() == null) {
            if (find(job).isEmpty()) {
                return ActivityJobs.activityGone(job);
            }
            throw new PermanentJobFailure("The inbox URL doesn't have a host");
        }
        var host = inbox.getHost().toLowerCase(Locale.ROOT);

        var now = Instant.now();
        if (!gate.tryAcquire(host)) {
            var busySince = job.busySince() == null ? now : job.busySince();
            if (busySince.plus(Jobs.RETRIED_FOR).isAfter(now)) {
                return Jobs.waitUntil(gate.nextTurn(host, now), job.withBusySince(busySince));
            }
            // Something else, like its actor's suspension, could be what the job should wait for now, which starts it again.
            return switch (check(job.withBusySince(null), inbox, host, now)) {
                case Checked.Finished finished -> finished.handler();
                case Checked.Ready _ ->
                        throw new PermanentJobFailure("Gave up because " + host + " has been too busy for too long");
            };
        }
        try {
            // It's had its turn, so a later wait for one starts again.
            var notBusy = job.withBusySince(null);
            return switch (check(notBusy, inbox, host, now)) {
                case Checked.Finished finished -> finished.handler();
                case Checked.Ready ready -> sendOrWait(notBusy, ready, inbox, host, now);
            };
        } finally {
            gate.release(host);
        }
    }

    private CompletionHandler<DeliveryJob> sendOrWait(DeliveryJob job, Checked.Ready ready, URI inbox, String host, Instant now) {
        var backingOff = gate.backingOffUntil(host, now);
        if (backingOff.isPresent()) {
            if (job.isOverdue(now)) {
                throw new PermanentJobFailure("Gave up because " + host + " has been failing for too long");
            }
            return later(backingOff.get(), job);
        }
        if ((job.staysBehind() != null || job.staysBehindPrefix() != null)
                && isRunningFor(job.staysBehind(), job.staysBehindPrefix(), host)) {
            return Jobs.waitUntil(now.plus(BEHIND_DELAY), job);
        }
        send(ready.activity(), ready.signer(), inbox, host);
        return Jobs.done();
    }

    /**
     * Looks for what ends a job without sending it, or holds it back whatever the host's state.
     */
    private Checked check(DeliveryJob job, URI inbox, String host, Instant now) {
        var activity = find(job);
        if (activity.isEmpty()) {
            return new Checked.Finished(ActivityJobs.activityGone(job));
        }
        if (blocks.isBlocked(host)) {
            // It was queued before the block. This comes before backing off, which could end with it failing.
            return new Checked.Finished(Jobs.done());
        }
        var signer = activity.get().isSignedByInstance()
                ? Optional.<Actor>empty()
                : actors.findAnyLocalById(activity.get().actorId());
        var whileSuspended = activity.get().whileSuspended();
        if (whileSuspended != WhileSuspended.SEND && signer.filter(found -> !found.isActive()).isPresent()) {
            // It was queued before a suspension or deletion.
            if (signer.get().isDeleted() || whileSuspended == WhileSuspended.DROP || ActivityJobs.hasOutlivedItsActivity(job)) {
                return new Checked.Finished(Jobs.done());
            }
            return new Checked.Finished(Jobs.waitUntil(now.plus(SUSPENDED_DELAY), job));
        }
        if (!actors.suspendedInboxes(List.of(inbox)).isEmpty()) {
            return new Checked.Finished(Jobs.done());
        }
        return new Checked.Ready(activity.get(), signer);
    }

    private sealed interface Checked {

        record Finished(CompletionHandler<DeliveryJob> handler) implements Checked {
        }

        record Ready(OutboundActivity activity, Optional<Actor> signer) implements Checked {
        }
    }

    /**
     * By host and not by inbox, because a server's followers can have moved to its shared inbox since. A job left picked by a
     * node that died isn't running, and when it's picked up again it finds its activity cancelled.
     */
    private boolean isRunningFor(String activityId, String activityIdPrefix, String host) {
        return jdbc.sql("""
                        SELECT task.data ->> 'inbox' FROM scheduled_tasks
                        CROSS JOIN LATERAL (SELECT convert_from(task_data, 'UTF8')::json AS data) task
                        WHERE task_name = :task AND picked AND last_heartbeat > now() - CAST(:runLasts AS interval)
                          AND (task.data ->> 'activityId' = :id
                               OR starts_with(task.data ->> 'activityId', CAST(:prefix AS text)))
                        """)
                .param("task", ActivityDeliveries.TASK.getTaskName())
                .param("id", activityId, Types.VARCHAR)
                .param("prefix", activityIdPrefix, Types.VARCHAR)
                .param("runLasts", RUN_LASTS.toSeconds() + " seconds")
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
        var started = System.nanoTime();
        try {
            response = sender.post(inbox, activity.body().getBytes(StandardCharsets.UTF_8), key);
        } catch (IllegalArgumentException e) {
            recordRequest("invalid_inbox", started);
            throw new PermanentJobFailure("The inbox URL can't be signed", e);
        } catch (RuntimeException e) {
            recordRequest("error", started);
            throw e;
        } catch (BlockedTargetException e) {
            recordRequest("not_allowed", started);
            if (blocks.isBlocked(host)) {
                // Blocked since this job's check, so this finishes as it would have then.
                return;
            }
            // This extends IOException, but it's about where the request was going and not about the server's health.
            throw new PermanentJobFailure("The inbox URL isn't allowed", e);
        } catch (ResponseTooLargeException e) {
            // The host answered, and would answer the same way again, having probably received the activity each time.
            gate.succeeded(host);
            recordRequest("too_large", started);
            throw new PermanentJobFailure("The inbox's answer was too large", e);
        } catch (ConnectionRequestTimeoutException e) {
            // This server's own connections were all busy, which says nothing about the host's health.
            recordRequest("no_connection", started);
            throw new UncheckedIOException(e);
        } catch (IOException e) {
            gate.failed(host, Instant.now(), Duration.ZERO);
            recordRequest("unreachable", started);
            throw new UncheckedIOException(e);
        }

        var status = response.status();
        if (status >= 500 || status == 408 || status == 429) {
            var now = Instant.now();
            gate.failed(host, now, response.retryAfterDelay(now));
            recordRequest("retryable", started);
            throw new TemporaryDeliveryFailure("The inbox answered with a " + status);
        }
        gate.succeeded(host);
        if (status < 200 || status >= 300) {
            recordRequest("refused", started);
            throw new PermanentJobFailure("The inbox answered with a " + status);
        }
        recordRequest("delivered", started);
    }

    /**
     * Tagged by outcome and not by host, since there's a value for every server this one has ever sent to. The time includes
     * waiting for one of this server's connections, which is what {@code no_connection} runs out of.
     */
    private void recordRequest(String outcome, long startedNanos) {
        Timer.builder("elysium.federation.deliveries")
                .description("Requests to deliver an activity to another server's inbox")
                .tag("outcome", outcome)
                .register(meters)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Waits for a host's backoff to end. The time is spread out, so that all of the host's jobs don't arrive at once.
     */
    private static CompletionHandler<DeliveryJob> later(Instant time, DeliveryJob job) {
        return Jobs.waitUntil(time.plusMillis(ThreadLocalRandom.current().nextLong(BACKOFF_SPREAD.toMillis())), job);
    }

    private static final class TemporaryDeliveryFailure extends RuntimeException {
        TemporaryDeliveryFailure(String message) {
            super(message);
        }
    }
}
