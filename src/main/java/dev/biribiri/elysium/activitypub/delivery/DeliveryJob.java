package dev.biribiri.elysium.activitypub.delivery;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Sends one activity to one inbox. The activity itself is in {@link ActivityDeliveries}'s table.
 *
 * @param staysBehind an activity this one shouldn't overtake, or null
 */
record DeliveryJob(String activityId, String inbox, Instant queuedAt, String staysBehind) implements QueuedJob {
}
