package dev.biribiri.elysium.activitypub.delivery;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Sends one activity to one inbox. The activity itself is in {@link ActivityDeliveries}'s table.
 */
record DeliveryJob(String activityId, String inbox, Instant queuedAt) implements QueuedJob {
}
