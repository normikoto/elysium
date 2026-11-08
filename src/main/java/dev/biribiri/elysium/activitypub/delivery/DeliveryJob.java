package dev.biribiri.elysium.activitypub.delivery;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Sends one activity to one inbox. The activity itself is in {@link ActivityDeliveries}'s table.
 *
 * @param staysBehind       an activity this one shouldn't overtake, or null
 * @param staysBehindPrefix what the IDs of other activities this one shouldn't overtake start with, or null, as it is for a
 *                          job queued before this was added
 */
record DeliveryJob(String activityId, String inbox, Instant queuedAt, String staysBehind, String staysBehindPrefix)
        implements QueuedJob {
}
