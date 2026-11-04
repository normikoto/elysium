package dev.biribiri.elysium.activitypub.delivery;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.SignedSender;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

    DeliveryTask(JdbcClient jdbc, ActorService actors, ActorSigningKeys keys, SignedSender sender, HostGate gate) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.keys = keys;
        this.sender = sender;
        this.gate = gate;
    }

    @Bean
    Task<DeliveryJob> activityDelivery() {
        return Jobs.task(ActivityDeliveries.TASK, (instance, context) -> run(instance.getData()));
    }

    private CompletionHandler<DeliveryJob> run(DeliveryJob job) {
        var activity = jdbc.sql("SELECT actor_id, body FROM outbound_activity WHERE activity_id = :id")
                .param("id", job.activityId())
                .query((rs, row) -> new StoredActivity(rs.getObject("actor_id", UUID.class), rs.getString("body")))
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

        var now = Instant.now();
        var backingOff = gate.backingOffUntil(host, now);
        if (backingOff.isPresent()) {
            if (Jobs.isOverdue(job, now)) {
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

    private void send(StoredActivity activity, URI inbox, String host) {
        var key = actors.findLocalById(activity.actorId())
                .flatMap(keys::forActor)
                .orElseThrow(() -> new PermanentJobFailure("The activity's actor no longer has a local key"));
        Response response;
        try {
            response = sender.post(inbox, activity.body().getBytes(StandardCharsets.UTF_8), key);
        } catch (IllegalArgumentException e) {
            throw new PermanentJobFailure("The inbox URL can't be signed", e);
        } catch (BlockedTargetException e) {
            // This extends IOException, but it's about where the request was going and not about the server's health.
            throw new PermanentJobFailure("The inbox URL isn't allowed", e);
        } catch (IOException e) {
            gate.failed(host, Instant.now(), null);
            throw new UncheckedIOException(e);
        }

        var status = response.status();
        if (status >= 200 && status < 300) {
            gate.succeeded(host);
        } else if (status >= 500 || status == 408 || status == 429) {
            var now = Instant.now();
            gate.failed(host, now, retryAfter(response, now));
            throw new TemporaryDeliveryFailure("The inbox answered with a " + status);
        } else {
            gate.succeeded(host);
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

    /**
     * @return how long the header asks this server to wait, in either of its forms, or null if it doesn't say
     */
    private static Duration retryAfter(Response response, Instant now) {
        var value = response.retryAfter();
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (NumberFormatException | ArithmeticException notSeconds) {
            try {
                var date = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(now, date);
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    private record StoredActivity(UUID actorId, String body) {
    }

    private static final class TemporaryDeliveryFailure extends RuntimeException {
        TemporaryDeliveryFailure(String message) {
            super(message);
        }
    }
}
