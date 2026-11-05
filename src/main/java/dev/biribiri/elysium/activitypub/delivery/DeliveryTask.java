package dev.biribiri.elysium.activitypub.delivery;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.activitypub.http.ResponseTooLargeException;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.SignedSender;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends one activity to one inbox. No database transaction is open during this, so a slow server doesn't hold a connection.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryTask {

    private static final Duration BUSY_DELAY = Duration.ofSeconds(5);

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

    private CompletionHandler<DeliveryJob> run(DeliveryJob job) {
        var activity = jdbc.sql("SELECT actor_id, body FROM outbound_activity WHERE activity_id = :id")
                .param("id", job.activityId())
                .query((rs, row) -> new OutboundActivity(
                        job.activityId(), rs.getObject("actor_id", UUID.class), rs.getString("body")))
                .optional();
        if (activity.isEmpty()) {
            // It was cancelled, or this is a retry from after it was cleaned up.
            return Jobs.done();
        }

        var inbox = URI.create(job.inbox());
        if (inbox.getHost() == null) {
            throw new PermanentJobFailure("The inbox URL doesn't have a host");
        }
        var host = inbox.getHost().toLowerCase(Locale.ROOT);
        if (blocks.isBlocked(host)) {
            // It was queued before the block. This isn't the server failing, so it doesn't count as a failure.
            return Jobs.done();
        }

        var now = Instant.now();
        var backingOff = gate.backingOffUntil(host, now);
        if (backingOff.isPresent()) {
            if (job.isOverdue(now)) {
                throw new PermanentJobFailure("Gave up because " + host + " has been failing for too long");
            }
            return later(backingOff.get());
        }
        if (!gate.tryAcquire(host)) {
            return later(now.plus(BUSY_DELAY));
        }
        try {
            send(activity.get(), inbox, host);
            return Jobs.done();
        } finally {
            gate.release(host);
        }
    }

    void send(OutboundActivity activity, URI inbox, String host) {
        var key = actors.findLocalById(activity.actorId())
                .flatMap(keys::forActor)
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
     * Waits without counting as a failure, because nothing about this job has gone wrong. The time is spread out, so that
     * all of a host's jobs don't arrive at once.
     */
    private static CompletionHandler<DeliveryJob> later(Instant time) {
        var spread = ThreadLocalRandom.current().nextLong(BUSY_DELAY.toMillis() * 2);
        return (complete, operations) -> operations.reschedule(complete, time.plusMillis(spread));
    }

    private static final class TemporaryDeliveryFailure extends RuntimeException {
        TemporaryDeliveryFailure(String message) {
            super(message);
        }
    }
}
