package dev.biribiri.elysium.activitypub.delivery;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Sends one activity to one inbox. The activity itself is in {@link ActivityDeliveries}'s table.
 *
 * @param staysBehind       an activity this one shouldn't overtake, or null
 * @param staysBehindPrefix what the IDs of other activities this one shouldn't overtake start with, or null, as it is for a
 *                          job queued before this was added
 * @param busySince         when the job started waiting for a turn with its host, if it's still waiting, or null. It's only
 *                          that wait that gives up, so a job that waited for its actor's suspension, or that an admin
 *                          retries, doesn't give up the moment its host is busy.
 */
record DeliveryJob(
        String activityId, String inbox, Instant queuedAt, String staysBehind, String staysBehindPrefix, Instant busySince)
        implements QueuedJob {

    DeliveryJob withBusySince(Instant time) {
        return new DeliveryJob(activityId, inbox, queuedAt, staysBehind, staysBehindPrefix, time);
    }

    /**
     * A run only fails after it's had a turn.
     */
    @Override
    public DeliveryJob afterFailure() {
        return withBusySince(null);
    }
}
