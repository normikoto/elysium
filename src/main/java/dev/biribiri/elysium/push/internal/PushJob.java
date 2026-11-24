package dev.biribiri.elysium.push.internal;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Sends one notification to one subscription.
 */
public record PushJob(UUID notificationId, UUID subscriptionId, Instant queuedAt) implements QueuedJob {

    /**
     * How long push services are asked to keep a message, and so how long it's worth trying to send one. A notification
     * that arrives days late is only noise, since the client has had it from the API by then.
     */
    static final Duration TIME_TO_LIVE = Duration.ofHours(48);

    @Override
    public boolean isOverdue(Instant now) {
        return isStale(now);
    }

    boolean isStale(Instant now) {
        return !queuedAt.plus(TIME_TO_LIVE).isAfter(now);
    }
}
